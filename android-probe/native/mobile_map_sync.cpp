#include "mobile_map_sync.hpp"

#include <QDir>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSet>
#include <algorithm>
#include <limits>
#include <stdexcept>

namespace teyvat {
namespace {
QString settingsPath(const QString& directory) { return QDir(directory).filePath(QStringLiteral("hoyolab-android.ini")); }
QString key(const QString& profile, const char* field) { return profile + QLatin1Char('/') + QLatin1String(field); }
constexpr qint64 maxPoint = 1000000000;

QByteArray json(const QJsonObject& object) { return QJsonDocument(object).toJson(QJsonDocument::Compact); }
bool eligible(const HistoryEvent& event) {
    bool ok = false;
    const auto id = event.marker_id.toLongLong(&ok);
    return event.source == QStringLiteral("hoyolab-map-2") && ok && id > 0 && id <= maxPoint
        && event.origin != QStringLiteral("import") && is_confirmed(read_status(event.before)) != is_confirmed(read_status(event.after));
}
}

MobileMapSync::MobileMapSync(const QString& directory)
    : store_(QDir(directory).filePath(QStringLiteral("companion.db"))), settings_(settingsPath(directory), QSettings::IniFormat),
      profile_(store_.active_profile().id) {
    settings_.setFallbacksEnabled(false);
}

QSet<QString> MobileMapSync::remote(const QString& directory, const QString& profile) {
    QSettings settings(settingsPath(directory), QSettings::IniFormat);
    settings.setFallbacksEnabled(false);
    const auto ids = settings.value(key(profile, "remote")).toStringList();
    return ids.size() <= max_markers ? QSet<QString>(ids.cbegin(), ids.cend()) : QSet<QString>{};
}

void MobileMapSync::advance(const std::optional<HistoryEvent>& event) {
    settings_.setValue(key(profile_, "cursor"), event ? event->id : 0);
    settings_.setValue(key(profile_, "command"), event ? event->command : QString{});
    settings_.sync();
    if (settings_.status() != QSettings::NoError) { throw std::runtime_error("Could not save HoYoLAB sync progress."); }
}

QByteArray MobileMapSync::request() {
    if (store_.active_profile().id != profile_ || settings_.status() != QSettings::NoError) {
        throw std::runtime_error("Reopen HoYoLAB sync for the current profile.");
    }
    auto cursor = settings_.value(key(profile_, "cursor"), 0).toLongLong();
    if (cursor < 0 || cursor == std::numeric_limits<qint64>::max()) { throw std::runtime_error("Invalid HoYoLAB sync cursor."); }
    if (cursor > 0) {
        const auto previous = store_.history(profile_, 1, cursor + 1);
        if (previous.isEmpty() || previous.front().id != cursor || previous.front().command != settings_.value(key(profile_, "command")).toString()) {
            advance(std::nullopt);
            cursor = 0;
        }
    }
    const auto events = store_.history_after(profile_, cursor);
    pending_.reset();
    std::optional<HistoryEvent> skipped;
    for (auto it = events.cbegin(); it != events.cend(); ++it) {
        const bool superseded = std::any_of(it + 1, events.cend(), [&](const auto& next) {
            return next.source == it->source && next.marker_id == it->marker_id;
        });
        if (eligible(*it) && !superseded) { pending_ = *it; break; }
        skipped = *it;
    }
    if (skipped) { advance(skipped); }
    QJsonObject request{{"map", 2}, {"write", pending_.has_value()}};
    if (pending_) {
        request.insert("point", pending_->marker_id.toLongLong());
        request.insert("collected", is_confirmed(read_status(pending_->after)));
    }
    return json(request);
}

QByteArray MobileMapSync::accept(const QByteArray& response) {
    if (response.size() > max_transfer_bytes || store_.active_profile().id != profile_) {
        throw std::runtime_error("Invalid HoYoLAB sync response.");
    }
    const auto result = QJsonDocument::fromJson(response).object();
    const auto state = result.value("state").toString(QStringLiteral("loading"));
    if (state == QStringLiteral("ack") && (!pending_ || result.value("point").toInteger() != pending_->marker_id.toLongLong()
        || !result.value("collected").isBool() || result.value("collected").toBool() != is_confirmed(read_status(pending_->after)))) {
        return json({{"state", "not_acknowledged"}, {"changed", false}});
    }
    bool changed = false;
    if ((state == QStringLiteral("ready") || state == QStringLiteral("ack")) && result.value("ids").isArray()) {
        const auto list = result.value("ids").toArray();
        if (list.size() > max_markers) { throw std::runtime_error("HoYoLAB returned too many chest IDs."); }
        QSet<QString> ids;
        for (const auto& value : list) {
            const auto point = value.toInteger();
            if (point < 1 || point > maxPoint) { throw std::runtime_error("HoYoLAB returned an invalid chest ID."); }
            ids.insert(QString::number(point));
        }
        const auto previous = settings_.value(key(profile_, "remote")).toStringList();
        changed = ids != QSet<QString>(previous.cbegin(), previous.cend());
        if (changed) {
            settings_.setValue(key(profile_, "remote"), QStringList(ids.cbegin(), ids.cend()));
            settings_.sync();
            if (settings_.status() != QSettings::NoError) { throw std::runtime_error("Could not save HoYoLAB sync progress."); }
        }
    }
    if (state == QStringLiteral("ack")) {
        advance(pending_);
        pending_.reset();
    }
    return json({{"state", state}, {"changed", changed}});
}

} // namespace teyvat
