#pragma once

#include "teyvat/store.hpp"
#include <QSettings>

namespace teyvat {

class MobileMapSync final {
public:
    explicit MobileMapSync(const QString& directory);
    [[nodiscard]] QByteArray request();
    [[nodiscard]] QByteArray accept(const QByteArray& response);
    [[nodiscard]] static QSet<QString> remote(const QString& directory, const QString& profile);

private:
    void advance(const std::optional<HistoryEvent>& event);
    Store store_;
    QSettings settings_;
    QString profile_;
    std::optional<HistoryEvent> pending_;
};

} // namespace teyvat
