#include "mobile_navigation.hpp"
#include "mobile_map_sync.hpp"

#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <exception>
#include <stdexcept>

namespace teyvat {
namespace {

const QSize galaxySize(1170, 540);
const QRect galaxyMinimap(86, 14, 128, 128);
const QRect galaxyEye(204, 14, 28, 28);
constexpr qint64 maximumPoseAgeMs = 1500;
constexpr qint64 maximumConfirmationAgeMs = 12000;
constexpr qint64 positionIntervalMs = 450;
constexpr qint64 minimumRetryIntervalMs = 200;
constexpr qint64 openingIntervalMs = 100;
constexpr qint64 snapshotFreshSeconds = 15 * 60;

QString databasePath(const QString& directory) {
    if (directory.isEmpty() || !QDir::isAbsolutePath(directory)) {
        throw std::invalid_argument("Choose an absolute application data directory.");
    }
    return QDir(directory).filePath(QStringLiteral("companion.db"));
}

OfficialMap officialSurface(const QString& directory, std::stop_token stop, bool refresh) {
    const auto cache = QDir(directory).filePath(QStringLiteral("official-cache"));
    if (!refresh) { return load_official_map(cache, 2); }
    if (stop.stop_requested()) { throw std::runtime_error("Navigation stopped."); }
    const auto modified = QFileInfo(QDir(cache).filePath(QStringLiteral("map-2/snapshot.json"))).lastModified();
    const auto age = modified.secsTo(QDateTime::currentDateTimeUtc());
    if (modified.isValid() && age >= 0 && age <= snapshotFreshSeconds) {
        try { return load_official_map(cache, 2); }
        catch (const std::exception&) { /* Refresh an invalid local snapshot. */ }
    }
    try { return refresh_official_map(cache, 2, stop); }
    catch (const std::exception&) {
        if (stop.stop_requested()) { throw; }
        const auto refreshError = std::current_exception();
        try { return load_official_map(cache, 2); }
        catch (const std::exception&) { std::rethrow_exception(refreshError); }
    }
}

LocalizationConfig galaxyHud() {
    LocalizationConfig config;
    config.roi = galaxyMinimap;
    // Keep the Eye's center so resampling does not dilute the white glyph.
    config.presence_roi = galaxyEye;
    config.reference = QImage(128, 128, QImage::Format_RGB32);
    config.reference.fill(Qt::black);
    config.origin = {500000, 500000};
    config.method = LocalizationMethod::Orb;
    config.allow_rotation = true;
    config.scales = {.5, 4};
    return config;
}

TrackingSetup importSurface(const OfficialMap& map, const LocalizationConfig& hud, Store& store) {
    TrackingSetup calibration;
    calibration.capture_size = galaxySize;
    calibration.localization = hud;
    auto setup = make_official_tracking_setup(map, calibration);
    const auto profile = store.active_profile().id;
    const auto review = store.review_import(setup.dataset, profile, false);
    store.apply_import(setup.dataset, profile, false, review.token);
    return setup;
}

QByteArray statusJson(const char* state, const char* status, int automaticChanges = 0) {
    return QJsonDocument(QJsonObject{{"state", QLatin1String(state)}, {"status", QLatin1String(status)},
        {"automaticChanges", automaticChanges}})
        .toJson(QJsonDocument::Compact);
}

QPointF worldPosition(const RecordedPosition& position) { return {position.x, position.y}; }

QRect scaledRect(const QRect& rect, double scale) {
    const auto x = qRound(rect.x() * scale);
    const auto y = qRound(rect.y() * scale);
    return {x, y, qRound(rect.width() * scale), qRound(rect.height() * scale)};
}

} // namespace

MobileNavigation::MobileNavigation(QString filesDirectory, std::stop_token stop, bool refreshOfficial)
    : stop_(stop), store_(databasePath(filesDirectory)), map_(officialSurface(filesDirectory, stop, refreshOfficial)),
      reference_(galaxyHud()), tracking_(store_, importSurface(map_, reference_, store_)) {
    for (const auto& marker : tracking_.setup().dataset.markers) {
        if (!marker.retired) { chestPositions_.insert(marker.id, {marker.x, marker.y}); }
    }
    configure(0, 20, false);
    tracking_.set_remote_collected(MobileMapSync::remote(filesDirectory, store_.active_profile().id));
    matchWorker_.setMaxThreadCount(1);
    matchWorker_.setExpiryTimeout(-1);
}


void MobileNavigation::configure(int area, int nearDistance, bool autoCollect) {
    constexpr int areas[] = {0, 1, 1001, 2, 3, 4, 8, 11, 13, 16};
    if (std::find(std::begin(areas), std::end(areas), area) == std::end(areas)) {
        throw std::invalid_argument("Unknown navigation region.");
    }
    tracking_.set_near_distance(std::clamp(nearDistance, 5, 100));
    if (autoCollect_ && !autoCollect) { clearGuidance(); }
    autoCollect_ = autoCollect;
    if (area_ == area) { return; }
    area_ = area;
    QSet<QString> ids;
    const auto selected = official_region_markers(map_, area);
    for (auto i = chestPositions_.cbegin(); i != chestPositions_.cend(); ++i) {
        if (area == 0 || selected.contains(i.key())) { ids.insert(i.key()); }
    }
    tracking_.set_allowed_targets(std::move(ids));
}

MobileNavigation::~MobileNavigation() {
    matchStop_.request_stop();
    matchWorker_.waitForDone();
}

OfficialWorldLocator& MobileNavigation::locator() {
    if (!locator_) { locator_ = std::make_unique<OfficialWorldLocator>(map_, stop_); }
    return *locator_;
}

void MobileNavigation::clearGuidance(bool cancelMatch) {
    if (cancelMatch && matchResult_.valid()) { matchStop_.request_stop(); }
    tracking_.pause();
    liveAge_.invalidate();
    cameraPose_ = {};
    cameraDegrees_.reset();
    positionTimeMs_ = -1;
    headingNeedsRematch_ = false;
}

bool MobileNavigation::positionIsFresh(qint64 frameTimeMs) const {
    return positionTimeMs_ >= 0 && frameTimeMs - positionTimeMs_ < maximumPoseAgeMs
        && liveAge_.isValid() && liveAge_.elapsed() + processingMs_ < maximumPoseAgeMs;
}

void MobileNavigation::startLocalMatch(const QImage& image, qint64 timeMs) {
    // The JNI frame is borrowed. Only the small HUD union crosses threads.
    const auto bounds = reference_.roi.united(reference_.presence_roi);
    auto crop = image.copy(bounds);
    auto config = reference_;
    config.roi.translate(-bounds.topLeft());
    config.presence_roi.translate(-bounds.topLeft());
    matchStop_ = std::stop_source{};
    std::packaged_task<LocalizationResult()> task(
        [image = std::move(crop), config = std::move(config), stop = matchStop_.get_token()] {
            return localize_minimap(image, config, stop);
        });
    matchResult_ = task.get_future();
    matchAge_.start();
    pendingPositionTimeMs_ = lastMatchAttempt_ = timeMs;
    headingNeedsRematch_ = false;
    matchWorker_.start([task = std::move(task)]() mutable { task(); });
}

void MobileNavigation::pollLocalMatch(qint64 timeMs) {
    if (!matchResult_.valid()) { return; }
    if (matchAge_.elapsed() >= maximumPoseAgeMs || timeMs - pendingPositionTimeMs_ >= maximumPoseAgeMs) {
        matchStop_.request_stop();
    }
    if (matchResult_.wait_for(std::chrono::seconds::zero()) != std::future_status::ready) { return; }
    auto completed = std::move(matchResult_);
    if (matchStop_.stop_requested()) { return; }
    LocalizationResult estimate;
    try { estimate = completed.get(); }
    catch (...) { clearGuidance(); throw; }
    if (estimate.position) {
        acceptPosition(std::move(estimate), pendingPositionTimeMs_, matchAge_);
    } else {
        ++misses_;
        clearGuidance();
    }
}

void MobileNavigation::acceptPosition(LocalizationResult estimate, qint64 timeMs, const QElapsedTimer& age) {
    misses_ = 0;
    const auto hint = tracking_.view().last_position;
    const auto position = worldPosition(*estimate.position);
    if (!hint || std::hypot(position.x() - hint->x(), position.y() - hint->y()) > 64) {
        tracking_.reanchor(position);
    }
    tracking_.observe(estimate, {}, timeMs);
    cameraPose_ = std::move(estimate);
    cameraDegrees_.reset();
    processingMs_ = age.elapsed();
    positionTimeMs_ = timeMs;
    liveAge_.start();
}

void MobileNavigation::updateCamera(const QImage& image) {
    const auto heading = tracking_.view().guidance
        ? estimate_camera_heading(image, reference_, cameraPose_) : std::nullopt;
    if (heading) {
        cameraDegrees_ = heading->degrees;
        headingNeedsRematch_ = false;
    } else {
        // A rotating minimap can invalidate the last terrain orientation.
        // Stop drawing its bearing and bring forward one verified match.
        headingNeedsRematch_ |= cameraDegrees_.has_value();
        cameraDegrees_.reset();
    }
}

void MobileNavigation::observeOpening(const QImage& image, qint64 timeMs) {
    const auto& view = tracking_.view();
    if (!autoCollect_ || !view.guidance || view.guidance->map_units > tracking_.setup().analysis.radius
        || (lastSceneTime_ >= 0 && timeMs - lastSceneTime_ < openingIntervalMs)) { return; }
    lastSceneTime_ = timeMs;
    const int edge = std::clamp(qRound(image.height() * 512. / 1440), 256, std::min(640, image.height()));
    tracking_.observe_scene(image.copy((image.width() - edge) / 2, (image.height() - edge) / 2, edge, edge), timeMs);
}

QByteArray MobileNavigation::observeWorldMap(const QImage& image, bool force, qint64 timeMs) {
    if (force || lastWorldAttempt_ < 0 || timeMs - lastWorldAttempt_ >= 1500) {
        worldAnchorVisible_ = false;
        // The Android world-map acquisition is a search anchor. Only a separate
        // minimap match after returning to gameplay may produce live guidance.
        const int side = std::clamp(qRound(512. * image.height() / galaxySize.height()), 256,
            std::min(640, image.height()));
        const auto crop = image.copy((image.width() - side) / 2, (image.height() - side) / 2, side, side);
        if (official_world_map_marker_visible(crop)) {
            lastWorldAttempt_ = timeMs;
            const auto anchor = locator().locate(crop, stop_);
            if (anchor.position) {
                const auto position = worldPosition(*anchor.position);
                reference_ = official_local_reference(map_, position, reference_, stop_);
                tracking_.reanchor(position);
                anchored_ = true;
                misses_ = 0;
                worldAnchorVisible_ = true;
            }
        }
    }
    if (worldAnchorVisible_) { return statusJson("anchored", "Position acquired. Return to the game."); }
    return statusJson("waiting_for_map", "Open the in-game map with the player marker near the center.");
}

QByteArray MobileNavigation::observe(const QImage& frame, bool forceWorldMap, qint64 timeMs) {
    if (stop_.stop_requested()) { throw std::runtime_error("Navigation stopped."); }
    if (timeMs < 0 || timeMs <= lastFrameTime_) {
        clearGuidance();
        return statusJson("waiting", "Waiting for a fresh game frame.");
    }
    const bool frameGap = lastFrameTime_ >= 0 && timeMs - lastFrameTime_ > maximumPoseAgeMs;
    lastFrameTime_ = timeMs;
    if (frame.isNull() || frame.height() < 480 || frame.height() > galaxySize.height() * 4
        || std::abs(static_cast<double>(frame.width()) / frame.height()
            - static_cast<double>(galaxySize.width()) / galaxySize.height()) > .035) {
        hudPresent_ = false;
        worldAnchorVisible_ = false;
        clearGuidance();
        return statusJson("unsupported_hud", "Use landscape Genshin with the tested Galaxy HUD layout.");
    }

    QElapsedTimer processing;
    processing.start();
    if (frame.size() != frameSize_) {
        const double scale = static_cast<double>(frame.height()) / galaxySize.height();
        frameSize_ = frame.size();
        reference_.roi = scaledRect(galaxyMinimap, scale);
        reference_.presence_roi = scaledRect(galaxyEye, scale);
        reference_.presence_template = {};
        hudPresent_ = false;
    }
    const auto& image = frame;
    if (reference_.presence_template.isNull()) {
        if (const auto presence = learn_hud_presence(image, reference_)) { reference_.presence_template = *presence; }
    }
    const bool present = !reference_.presence_template.isNull() && minimap_present(image, reference_);
    const bool resumed = !hudPresent_ || frameGap;
    hudPresent_ = present;
    if (present) { worldAnchorVisible_ = false; }
    if (!present || forceWorldMap) {
        clearGuidance();
        pollLocalMatch(timeMs);
        if (present) { return statusJson("waiting_for_map", "Open the in-game map to reacquire position."); }
        if (matchResult_.valid()) { return statusJson("waiting_for_map", "Waiting for the in-game map."); }
        return observeWorldMap(image, forceWorldMap, timeMs);
    }
    if (resumed) { clearGuidance(); }
    pollLocalMatch(timeMs);

    const qint64 sinceMatch = lastMatchAttempt_ < 0 ? positionIntervalMs : timeMs - lastMatchAttempt_;
    const auto hint = tracking_.view().last_position;
    const auto offset = hint ? *hint - reference_.origin : QPointF{};
    const bool referenceDue = hint && (lastReferenceAttempt_ < 0 || timeMs - lastReferenceAttempt_ >= 3000)
        && ((!anchored_ && !hintAttempted_) || (anchored_ && (offset.x() < 220 || offset.y() < 220
            || offset.x() > 804 || offset.y() > 804)));
    if (positionIsFresh(timeMs)) {
        updateCamera(image);
        observeOpening(image, timeMs);
        const bool matchDue = sinceMatch >= positionIntervalMs
            || (headingNeedsRematch_ && sinceMatch >= minimumRetryIntervalMs);
        if (positionIsFresh(timeMs)) {
            if (matchResult_.valid() || !matchDue) { return guidance(); }
            if (!referenceDue) {
                startLocalMatch(image, timeMs);
                return guidance();
            }
        }
    }
    if (matchResult_.valid()) {
        clearGuidance(false);
        return statusJson("position_unknown", "Waiting for a fresh position match.");
    }
    if (!resumed && positionTimeMs_ < 0 && sinceMatch < minimumRetryIntervalMs) {
        return statusJson("position_unknown", "Position unknown. Open the in-game map briefly.");
    }
    lastMatchAttempt_ = timeMs;
    headingNeedsRematch_ = false;

    if (!hint && !anchored_ && !hintAttempted_ && (area_ == 0 || area_ == 1)) {
        // This official chest is near the field-test area. A nearby tile is only a search hint.
        // The normal visual quality gates still apply before a position is accepted.
        hintAttempted_ = true;
        const auto testChest = std::find_if(map_.dataset.markers.cbegin(), map_.dataset.markers.cend(),
            [&](const auto& marker) { return marker.id == QStringLiteral("1060") && marker.map == map_.surface(); });
        if (testChest != map_.dataset.markers.cend()) {
            try {
                reference_ = official_local_reference(map_, {testChest->x, testChest->y}, reference_, stop_);
                anchored_ = true;
            } catch (const std::exception&) { if (stop_.stop_requested()) { throw; } }
        }
    }
    if (referenceDue) {
        hintAttempted_ = true;
        lastReferenceAttempt_ = timeMs;
        try { reference_ = official_local_reference(map_, *hint, reference_, stop_); anchored_ = true; }
        catch (const std::exception&) { if (stop_.stop_requested()) { throw; } }
    }

    auto estimate = anchored_ ? localize_minimap(image, reference_, stop_) : LocalizationResult{};
    if (!estimate.position) {
        ++misses_;
        const qint64 sinceGlobal = lastGlobalAttempt_ < 0 ? 3000 : timeMs - lastGlobalAttempt_;
        if (sinceGlobal >= 1500 && global_recovery_due(anchored_, misses_, sinceGlobal, resumed, false)) {
            lastGlobalAttempt_ = timeMs;
            const auto region = area_ == 0 ? QRectF{} : official_region_bounds(map_, area_);
            if (const auto found = locator().locate_minimap(image, reference_, stop_, region)) {
                estimate = found->estimate;
                reference_ = found->reference;
                anchored_ = true;
            }
        }
    }
    if (!estimate.position) {
        clearGuidance();
        return statusJson("position_unknown", "Position unknown. Open the in-game map briefly.");
    }

    acceptPosition(std::move(estimate), timeMs, processing);
    updateCamera(image);
    if (!positionIsFresh(timeMs)) {
        clearGuidance();
        return statusJson("position_unknown", "Position acquired. Waiting for a fresh game frame.");
    }
    observeOpening(image, timeMs);
    return guidance();
}

QByteArray MobileNavigation::guidance() const {
    const auto& view = tracking_.view();
    if (!view.guidance) {
        if (view.state == TrackingState::NoTarget) {
            return statusJson("no_target", "No uncollected surface chest found.", view.automatic_changes);
        }
        return statusJson("position_unknown", "Waiting for a verified position.", view.automatic_changes);
    }
    const auto& target = *view.guidance;
    const bool hasBearing = target.minimap_bearing.has_value() && cameraDegrees_.has_value();
    const auto id = QString::fromStdString(target.target.id);
    QJsonObject result{{"state", view.near_target ? "near_target" : hasBearing ? "guiding" : "heading_unknown"},
        {"status", view.near_target ? "You are near a chest." : hasBearing ? "Follow the compass." : "Camera direction unknown."},
        {"target", view.target_label}, {"targetId", id},
        {"distance", target.map_units}, {"near", view.near_target}, {"approximate", true},
        {"positionTimeMs", positionTimeMs_}, {"automaticChanges", view.automatic_changes}};
    if (hasBearing) { result.insert("bearing", std::remainder(*target.minimap_bearing - *cameraDegrees_, 360.)); }
    if (const auto point = chestPositions_.constFind(id); point != chestPositions_.cend()) {
        result.insert("mapUrl", QStringLiteral("https://act.hoyolab.com/ys/app/interactive-map/index.html?lang=en-us#/map/2?shown_types=17,44,45,46,269&center=%1,%2&zoom=0")
            .arg(point->y() - official_coordinate_offset, 0, 'f', 3).arg(point->x() - official_coordinate_offset, 0, 'f', 3));
    }
    return QJsonDocument(result).toJson(QJsonDocument::Compact);
}

QByteArray MobileNavigation::confirmTarget() {
    if (stop_.stop_requested() || !liveAge_.isValid() || liveAge_.elapsed() + processingMs_ >= maximumConfirmationAgeMs
        || tracking_.view().limited_position || !tracking_.confirm_target()) {
        return statusJson("cannot_confirm", "Wait for a fresh verified target before confirming.");
    }
    auto result = QJsonDocument::fromJson(guidance()).object();
    result.insert("confirmed", true);
    return QJsonDocument(result).toJson(QJsonDocument::Compact);
}

QByteArray MobileNavigation::targetAction(int action, const QString& expected) {
    if (stop_.stop_requested()) { return statusJson("stale_target", "Navigation stopped."); }
    if (action == 3) {
        tracking_.undo();
        return guidance();
    }
    const auto& current = tracking_.view().guidance;
    if (!liveAge_.isValid() || liveAge_.elapsed() + processingMs_ >= maximumConfirmationAgeMs
        || !current || expected.isEmpty() || QString::fromStdString(current->target.id) != expected) {
        return statusJson("stale_target", "Wait for a fresh target before trying again.");
    }
    if (action == 1) { return confirmTarget(); }
    if (action == 2) {
        tracking_.skip();
        return guidance();
    }
    throw std::invalid_argument("Unknown navigation action.");
}

} // namespace teyvat
