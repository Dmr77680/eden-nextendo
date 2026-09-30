// SPDX-FileCopyrightText: Copyright 2026 citron Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <functional>

namespace Network {
class SocketBase;
}

namespace Service::SSL {

// A TLS backend can decrypt an entire record off the wire in one Read() call, leaving any
// plaintext beyond what the caller asked for buffered internally rather than on the raw socket.
// bsd:u's Poll only ever checks the raw socket, so it never reports that data as readable --
// the game can end up polling forever for bytes that already arrived and were already decrypted.
// This lets BSD::PollImpl ask "does this socket have SSL-buffered plaintext waiting?" without
// sockets/ depending on ssl/ for anything but this narrow query.
void RegisterPendingCheck(Network::SocketBase* socket, std::function<int()> check);
void UnregisterPendingCheck(Network::SocketBase* socket);
bool HasSslPendingData(Network::SocketBase* socket);

} // namespace Service::SSL
