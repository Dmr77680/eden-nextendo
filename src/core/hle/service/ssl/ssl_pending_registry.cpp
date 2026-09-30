// SPDX-FileCopyrightText: Copyright 2026 citron Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <mutex>
#include <unordered_map>

#include "core/hle/service/ssl/ssl_pending_registry.h"

namespace Service::SSL {

namespace {
std::mutex g_mutex;
std::unordered_map<Network::SocketBase*, std::function<int()>> g_checks;
} // namespace

void RegisterPendingCheck(Network::SocketBase* socket, std::function<int()> check) {
    if (!socket) {
        return;
    }
    std::scoped_lock lock{g_mutex};
    g_checks[socket] = std::move(check);
}

void UnregisterPendingCheck(Network::SocketBase* socket) {
    if (!socket) {
        return;
    }
    std::scoped_lock lock{g_mutex};
    g_checks.erase(socket);
}

bool HasSslPendingData(Network::SocketBase* socket) {
    if (!socket) {
        return false;
    }
    std::scoped_lock lock{g_mutex};
    const auto it = g_checks.find(socket);
    if (it == g_checks.end()) {
        return false;
    }
    return it->second() > 0;
}

} // namespace Service::SSL
