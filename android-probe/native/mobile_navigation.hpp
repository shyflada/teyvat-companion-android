#pragma once

#include "teyvat/official_map.hpp"
#include "teyvat/tracking.hpp"
#include <QByteArray>
#include <QElapsedTimer>
#include <QThreadPool>
#include <future>

namespace teyvat {

// Construct, observe, confirm and destroy on the same worker thread.
class MobileNavigation final {
public:
    explicit MobileNavigation(QString filesDirectory, std::stop_token stop = {}, bool refreshOfficial = true);
    ~MobileNavigation();
    [[nodiscard]] QByteArray observe(const QImage& frame, bool forceWorldMap, qint64 timeMs);
    [[nodiscard]] QByteArray confirmTarget();
    [[nodiscard]] QByteArray targetAction(int action, const QString& expected);
    void configure(int area, int nearDistance, bool autoCollect);

private:
    OfficialWorldLocator& locator();
    [[nodiscard]] QByteArray observeWorldMap(const QImage& image, bool force, qint64 timeMs);
    [[nodiscard]] QByteArray guidance() const;
    [[nodiscard]] bool positionIsFresh(qint64 frameTimeMs) const;
    void startLocalMatch(const QImage& image, qint64 timeMs);
    void pollLocalMatch(qint64 timeMs);
    void acceptPosition(LocalizationResult estimate, qint64 timeMs, const QElapsedTimer& age);
    void updateCamera(const QImage& image);
    void observeOpening(const QImage& image, qint64 timeMs);
    void clearGuidance(bool cancelMatch = true);

    std::stop_token stop_;
    Store store_;
    OfficialMap map_;
    std::unique_ptr<OfficialWorldLocator> locator_;
    LocalizationConfig reference_;
    TrackingSession tracking_;
    QHash<QString, QPointF> chestPositions_;
    QSize frameSize_;
    QElapsedTimer liveAge_;
    LocalizationResult cameraPose_;
    std::optional<double> cameraDegrees_;
    qint64 lastFrameTime_ = -1;
    qint64 lastMatchAttempt_ = -1;
    qint64 positionTimeMs_ = -1;
    qint64 lastWorldAttempt_ = -1;
    qint64 lastGlobalAttempt_ = -1;
    qint64 lastReferenceAttempt_ = -1;
    qint64 processingMs_ = 0;
    qint64 lastSceneTime_ = -1;
    int area_ = -1;
    bool autoCollect_ = false;
    int misses_ = 0;
    bool anchored_ = false;
    bool hudPresent_ = false;
    bool worldAnchorVisible_ = false;
    bool hintAttempted_ = false;
    bool headingNeedsRematch_ = false;
    QThreadPool matchWorker_;
    std::future<LocalizationResult> matchResult_;
    std::stop_source matchStop_;
    QElapsedTimer matchAge_;
    qint64 pendingPositionTimeMs_ = -1;
};

} // namespace teyvat
