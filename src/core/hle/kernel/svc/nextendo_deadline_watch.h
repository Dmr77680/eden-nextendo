// SPDX-FileCopyrightText: Copyright 2026 citron Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <atomic>

#include "common/common_types.h"

namespace Kernel::Svc {

// [Nextendo] Diagnostic-only: a short window during which svcWaitSynchronization logs any
// finite, plausible-length timeout it sees. Armed by sfdnsres.cpp right as the npln host
// resolution starts (see MaybeDelayNplnInit) and auto-expires a few seconds later -- this lets
// us directly OBSERVE whatever deadline Splatoon 3's gRPC stack is actually waiting against
// (if it's implemented as a timed kernel wait, which is the natural way to implement a
// completion-queue-with-deadline on this platform) without needing to find the constant via
// static binary analysis at all. 0 = disabled. Value is an absolute std::chrono::steady_clock
// milliseco/nd timestamp past which the watch turns itself off.
extern std::atomic<u64> g_nextendo_deadline_watch_until_ms;

void ArmNextendoDeadlineWatch(u64 duration_ms);

// Cheap check (one relaxed atomic load + one clock read) for other diagnostic call sites (e.g.
// KScheduler::SwitchThread) that want to log only while the watch is active, without duplicating
// the steady_clock comparison/auto-expiry logic that lives alongside the atomic itself.
bool IsNextendoDeadlineWatchActive();

} // namespace Kernel::Svc
