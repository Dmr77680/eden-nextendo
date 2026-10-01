// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2018 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <charconv>
#include <chrono>
#include <cstring>
#include <cstdlib>
#include <mutex>
#include <string_view>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

#include "common/settings.h"
#include "common/string_util.h"
#include "common/swap.h"
#include "core/core.h"
#include "core/hle/service/ipc_helpers.h"
#include "core/hle/service/sockets/sfdnsres.h"
#include "core/hle/service/sockets/sockets.h"
#include "core/hle/service/sockets/sockets_translate.h"
#include "core/internal_network/network.h"
#include "core/memory.h"

namespace Service::Sockets {

SFDNSRES::SFDNSRES(Core::System& system_) : ServiceFramework{system_, "sfdnsres"} {
    static const FunctionInfo functions[] = {
        {0, nullptr, "SetDnsAddressesPrivateRequest"},
        {1, nullptr, "GetDnsAddressPrivateRequest"},
        {2, &SFDNSRES::GetHostByNameRequest, "GetHostByNameRequest"},
        {3, nullptr, "GetHostByAddrRequest"},
        {4, nullptr, "GetHostStringErrorRequest"},
        {5, &SFDNSRES::GetGaiStringErrorRequest, "GetGaiStringErrorRequest"},
        {6, &SFDNSRES::GetAddrInfoRequest, "GetAddrInfoRequest"},
        {7, nullptr, "GetNameInfoRequest"},
        {8, nullptr, "RequestCancelHandleRequest"},
        {9, nullptr, "CancelRequest"},
        {10, &SFDNSRES::GetHostByNameRequestWithOptions, "GetHostByNameRequestWithOptions"},
        {11, nullptr, "GetHostByAddrRequestWithOptions"},
        {12, &SFDNSRES::GetAddrInfoRequestWithOptions, "GetAddrInfoRequestWithOptions"},
        {13, nullptr, "GetNameInfoRequestWithOptions"},
        {14, &SFDNSRES::ResolverSetOptionRequest, "ResolverSetOptionRequest"},
        {15, nullptr, "ResolverGetOptionRequest"},
    };
    RegisterHandlers(functions);
}

SFDNSRES::~SFDNSRES() = default;

static std::mutex g_last_host_mutex;
static std::unordered_map<std::string, std::string> g_last_host_for_ip;

void SetLastHostForIp(const std::string& ip, const std::string& host) {
    std::lock_guard lock(g_last_host_mutex);
    g_last_host_for_ip[ip] = host;
}

std::string GetLastHostForIp(const std::string& ip) {
    std::lock_guard lock(g_last_host_mutex);
    auto it = g_last_host_for_ip.find(ip);
    if (it != g_last_host_for_ip.end()) {
        return it->second;
    }
    return "";
}

// [Nextendo] See sfdnsres.h's declaration comment. Real bug this exists for (Splatoon 3,
// confirmed via Ryujinx-Nextendo hitting the identical failure on the same guest binary):
// its gRPC channel resolves a redirected Nextendo hostname correctly here, then later loses
// that address in its own connection-establishment plumbing and calls connect() with a
// zeroed IP -- but the SAME port it originally resolved for. Recording "port -> resolved IP"
// at resolution time lets BSD::ConnectImpl recover the real address for that exact port.
static std::mutex g_last_ip_for_port_mutex;
static std::unordered_map<u16, Network::IPv4Address> g_last_ip_for_port;

void SetLastIpForPort(u16 port, Network::IPv4Address ip) {
    if (port == 0) {
        return;
    }
    std::lock_guard lock(g_last_ip_for_port_mutex);
    g_last_ip_for_port[port] = ip;
}

std::optional<Network::IPv4Address> GetLastIpForPort(u16 port) {
    if (port == 0) {
        return std::nullopt;
    }
    std::lock_guard lock(g_last_ip_for_port_mutex);
    auto it = g_last_ip_for_port.find(port);
    if (it != g_last_ip_for_port.end()) {
        return it->second;
    }
    return std::nullopt;
}

// No server address is baked in: unconfigured builds fall back to loopback and redirect nowhere.
static std::string GetConfiguredIp(const std::string& setting, const char* env_var) {
    if (!setting.empty()) {
        return setting;
    }
    if (const char* env = std::getenv(env_var); env && *env) {
        return env;
    }
    return "127.0.0.1";
}

// [Nextendo] La redirection est-elle active ?
//
// Le reglage « enable_nextendo » n'existe QUE dans la facade Qt (src/citron/main.cpp) : la facade
// SDL (citron_cmd) ne le cable nulle part et le reecrit a sa valeur par defaut, false, au
// demarrage. Mesure du 2026-08-25 : lance par citron-cmd, Splatoon 3 a resolu
// « t-dce9377b-lp1.lp1.t.npln.srv.nintendo.net » vers 34.49.112.177 — le VRAI serveur de Nintendo —
// alors que le fichier de configuration portait bien enable_nextendo=true.
//
// On accepte donc aussi une activation par l'environnement, exactement comme GetConfiguredIp le
// fait deja pour les deux adresses. Une valeur vide, « 0 », « false » ou « no » ne l'active pas.
static bool RedirectionNextendoActive() {
    if (Settings::values.enable_nextendo.GetValue()) {
        return true;
    }
    const char* env = std::getenv("NEXTENDO_ENABLE");
    if (env == nullptr || *env == '\0') {
        return false;
    }
    const std::string v = Common::ToLower(env);
    return v != "0" && v != "false" && v != "no" && v != "off";
}

static std::optional<std::string> GetNextendoRedirectIp(const std::string& host) {
    if (!RedirectionNextendoActive()) {
        return std::nullopt;
    }

    const std::string server_ip =
        GetConfiguredIp(Settings::values.nextendo_server_ip.GetValue(), "NEXTENDO_SERVER_IP");
    const std::string nat_ip =
        GetConfiguredIp(Settings::values.nextendo_nat_ip.GetValue(), "NEXTENDO_NAT_IP");

    if (host.starts_with("nncs2-") && host.ends_with(".n.n.srv.nintendo.net")) {
        LOG_INFO(Service, "[Nextendo] Redirecting NAT check host '{}' -> '{}'", host, nat_ip);
        return nat_ip;
    }

    if (host == "nintendo.net" || host.ends_with(".nintendo.net") ||
        host == "nintendo.com" || host.ends_with(".nintendo.com") ||
        host == "nintendowifi.net" || host.ends_with(".nintendowifi.net") ||
        host == "nintendo.co.jp" || host.ends_with(".nintendo.co.jp")) {
        LOG_INFO(Service, "[Nextendo] Redirecting Nintendo host '{}' -> '{}'", host, server_ip);
        return server_ip;
    }

    return std::nullopt;
}


enum class NetDbError : s32 {
    Internal = -1,
    Success = 0,
    HostNotFound = 1,
    TryAgain = 2,
    NoRecovery = 3,
    NoData = 4,
};

static const constexpr std::array blockedDomains = {
    "srv.nintendo.net", //obvious
    "nintendo.es",
    "nintendowifi.net",
    "nintendo-europe.com",
    "nintendo.com.hk",
    "nintendo.com.au",
    "nintendo.co.kr",
    "nintendo.co.uk",
    "nintendo.co.jp",
    "nintendo.co.nz",
    "nintendo.co.za",
    "nintendo.com",
    "nintendo.jp",
    "nintendo.tw",
    "nintendo.at",
    "nintendo.be",
    "nintendo.dk",
    "nintendo.de",
    "nintendo.fi",
    "nintendo.fr",
    "nintendo.gr",
    "nintendo.hu",
    "nintendo.it",
    "nintendo.nl",
    "nintendo.no",
    "nintendo.pt",
    "nintendo.ru",
    "nintendo.ch",
    "nintendo.se",
    "nintendoswitch.com.cn",
    "nintendoswitch.com",
    "sun.hac.lp1.d4c.nintendo.net",
    "phoenix-api.wbagora.com", //hogwarts legacy
    "battle.net",
    "microsoft.com", // Minecraft dungeons + other games
    "mojang.com",
    "xboxlive.com",
    "api.epicgames.dev", // marvel cosmic invasion +?
    "minecraftservices.com",
    "508223012e5a5ff19f30a391b2bdadc0.my.2k.com", // Civilization 5
};

static bool IsBlockedHost(const std::string& host) {
    return std::any_of(
        blockedDomains.begin(), blockedDomains.end(),
        [&host](const std::string& domain) { return host.find(domain) != std::string::npos; });
}

static NetDbError GetAddrInfoErrorToNetDbError(GetAddrInfoError result) {
    // These combinations have been verified on console (but are not
    // exhaustive).
    switch (result) {
    case GetAddrInfoError::SUCCESS:
        return NetDbError::Success;
    case GetAddrInfoError::AGAIN:
        return NetDbError::TryAgain;
    case GetAddrInfoError::NODATA:
        return NetDbError::HostNotFound;
    case GetAddrInfoError::SERVICE:
        return NetDbError::Success;
    default:
        return NetDbError::HostNotFound;
    }
}

static Errno GetAddrInfoErrorToErrno(GetAddrInfoError result) {
    // These combinations have been verified on console (but are not
    // exhaustive).
    switch (result) {
    case GetAddrInfoError::SUCCESS:
        // Note: Sometimes a successful lookup sets errno to EADDRNOTAVAIL for
        // some reason, but that doesn't seem useful to implement.
        return Errno::SUCCESS;
    case GetAddrInfoError::AGAIN:
        return Errno::SUCCESS;
    case GetAddrInfoError::NODATA:
        return Errno::SUCCESS;
    case GetAddrInfoError::SERVICE:
        return Errno::INVAL;
    default:
        return Errno::SUCCESS;
    }
}

template <typename T>
static void Append(std::vector<u8>& vec, T t) {
    const size_t offset = vec.size();
    vec.resize(offset + sizeof(T));
    std::memcpy(vec.data() + offset, &t, sizeof(T));
}

static void AppendNulTerminated(std::vector<u8>& vec, std::string_view str) {
    const size_t offset = vec.size();
    vec.resize(offset + str.size() + 1);
    std::memmove(vec.data() + offset, str.data(), str.size());
}

// We implement gethostbyname using the host's getaddrinfo rather than the
// host's gethostbyname, because it simplifies portability: e.g., getaddrinfo
// behaves the same on Unix and Windows, unlike gethostbyname where Windows
// doesn't implement h_errno.
static std::vector<u8> SerializeAddrInfoAsHostEnt(const std::vector<Network::AddrInfo>& vec,
                                                  std::string_view host) {

    std::vector<u8> data;
    // h_name: use the input hostname (append nul-terminated)
    AppendNulTerminated(data, host);
    // h_aliases: leave empty

    Append<u32_be>(data, 0); // count of h_aliases
    // (If the count were nonzero, the aliases would be appended as nul-terminated here.)
    Append<u16_be>(data, static_cast<u16>(Domain::INET)); // h_addrtype
    Append<u16_be>(data, sizeof(Network::IPv4Address));   // h_length
    // h_addr_list:
    size_t count = vec.size();
    ASSERT(count <= UINT32_MAX);
    Append<u32_be>(data, static_cast<uint32_t>(count));
    for (const Network::AddrInfo& addrinfo : vec) {
        // On the Switch, this is passed through htonl despite already being
        // big-endian, so it ends up as little-endian.
        Append<u32_le>(data, Network::IPv4AddressToInteger(addrinfo.addr.ip));

        LOG_INFO(Service, "Resolved host '{}' to IPv4 address {}", host,
                 Network::IPv4AddressToString(addrinfo.addr.ip));
    }
    return data;
}

static std::pair<u32, GetAddrInfoError> GetHostByNameRequestImpl(HLERequestContext& ctx) {
    struct InputParameters {
        u8 use_nsd_resolve;
        u32 cancel_handle;
        u64 process_id;
    };
    static_assert(sizeof(InputParameters) == 0x10);

    IPC::RequestParser rp{ctx};
    const auto parameters = rp.PopRaw<InputParameters>();

    LOG_WARNING(
        Service,
        "called with ignored parameters: use_nsd_resolve={}, cancel_handle={}, process_id={}",
        parameters.use_nsd_resolve, parameters.cancel_handle, parameters.process_id);

    const auto host_buffer = ctx.ReadBuffer(0);
    std::string host = Common::StringFromBuffer(host_buffer);

    LOG_INFO(Service, "[Nextendo] DNS resolve (GetHostByName) requested: host={}", host);


    // [Nextendo] Redirect Nintendo hosts to the Nextendo servers when enabled.
    std::string query_host = host;
    auto redirect = GetNextendoRedirectIp(host);
    if (redirect.has_value()) {
        query_host = *redirect;
    } else if (IsBlockedHost(host)) {
        LOG_WARNING(Network, "Resolution of hostname {} requested, returning EAI_AGAIN", host);
        return {0, GetAddrInfoError::AGAIN};
    }

    auto res_v = Network::GetAddressInfo(query_host, /*service*/ std::nullopt);
    if (auto* res = std::get_if<std::vector<Network::AddrInfo>>(&res_v)) {
        if (redirect.has_value()) {
            for (const auto& addrinfo : *res) {
                SetLastHostForIp(Network::IPv4AddressToString(addrinfo.addr.ip), host);
            }
        }
        const std::vector<u8> data = SerializeAddrInfoAsHostEnt(*res, host);
        const u32 data_size = u32(data.size());
        ctx.WriteBuffer(data, 0);
        return {data_size, GetAddrInfoError::SUCCESS};
    }
    auto* err = std::get_if<Network::GetAddrInfoError>(&res_v);
    return {0, Translate(*err)};
}

void SFDNSRES::GetHostByNameRequest(HLERequestContext& ctx) {
    auto [data_size, emu_gai_err] = GetHostByNameRequestImpl(ctx);

    struct OutputParameters {
        NetDbError netdb_error;
        Errno bsd_errno;
        u32 data_size;
    };
    static_assert(sizeof(OutputParameters) == 0xc);

    IPC::ResponseBuilder rb{ctx, 5};
    rb.Push(ResultSuccess);
    rb.PushRaw(OutputParameters{
        .netdb_error = GetAddrInfoErrorToNetDbError(emu_gai_err),
        .bsd_errno = GetAddrInfoErrorToErrno(emu_gai_err),
        .data_size = data_size,
    });
}

void SFDNSRES::GetHostByNameRequestWithOptions(HLERequestContext& ctx) {
    auto [data_size, emu_gai_err] = GetHostByNameRequestImpl(ctx);

    struct OutputParameters {
        u32 data_size;
        NetDbError netdb_error;
        Errno bsd_errno;
    };
    static_assert(sizeof(OutputParameters) == 0xc);

    IPC::ResponseBuilder rb{ctx, 5};
    rb.Push(ResultSuccess);
    rb.PushRaw(OutputParameters{
        .data_size = data_size,
        .netdb_error = GetAddrInfoErrorToNetDbError(emu_gai_err),
        .bsd_errno = GetAddrInfoErrorToErrno(emu_gai_err),
    });
}

static std::vector<u8> SerializeAddrInfo(const std::vector<Network::AddrInfo>& vec,
                                         std::string_view host) {
    // Adapted from
    // https://github.com/switchbrew/libnx/blob/c5a9a909a91657a9818a3b7e18c9b91ff0cbb6e3/nx/source/runtime/resolver.c#L190
    std::vector<u8> data;

    for (const Network::AddrInfo& addrinfo : vec) {
        // serialized addrinfo:
        Append<u32_be>(data, 0xBEEFCAFE);                                        // magic
        Append<u32_be>(data, 0);                                                 // ai_flags
        Append<u32_be>(data, static_cast<u32>(Translate(addrinfo.family)));      // ai_family
        Append<u32_be>(data, static_cast<u32>(Translate(addrinfo.socket_type))); // ai_socktype
        Append<u32_be>(data, static_cast<u32>(Translate(addrinfo.protocol)));    // ai_protocol
        Append<u32_be>(data, 16); // ai_addrlen
        // ^ *not* sizeof(SerializedSockAddrIn), not that it matters since they're the same size

        // ai_addr: BSD-style sockaddr_in, matching the SockAddrIn struct in sockets.h --
        // {u8 sin_len; u8 sin_family; u16 sin_port; u8 sin_addr[4]; u8 sin_zero[8];}. This used
        // to write sin_family as a single 2-byte big-endian value, which skips sin_len entirely
        // (leaving it implicitly 0x00) instead of emitting it as its own leading byte. A guest
        // resolver walker that trusts sin_len -- and gRPC-based titles (Splatoon 3) that build
        // their own connect() sockaddr straight out of this buffer -- reads a zero-length
        // address off a sin_len of 0 and falls back to connecting to 0.0.0.0. Exactly matches
        // Ryujinx-Nextendo's AddrInfo4.Length fix (was sizeof(Array4<byte>)=4, needed to be
        // sizeof(AddrInfo4)=16): sin_len must be the full sockaddr size, not folded away.
        // [Nextendo]
        Append<u8>(data, static_cast<u8>(sizeof(SockAddrIn)));              // sin_len
        Append<u8>(data, static_cast<u8>(Translate(addrinfo.addr.family))); // sin_family
        // On the Switch, the following fields are passed through htonl despite
        // already being big-endian, so they end up as little-endian.
        Append<u16_le>(data, addrinfo.addr.portno);                            // sin_port
        Append<u32_le>(data, Network::IPv4AddressToInteger(addrinfo.addr.ip)); // sin_addr
        data.resize(data.size() + 8, 0);                                       // sin_zero

        if (addrinfo.canon_name.has_value()) {
            AppendNulTerminated(data, *addrinfo.canon_name);
        } else {
            data.push_back(0);
        }

        LOG_INFO(Service, "Resolved host '{}' to IPv4 address {}", host,
                 Network::IPv4AddressToString(addrinfo.addr.ip));
    }

    data.resize(data.size() + 4, 0); // 4-byte sentinel value

    return data;
}

static std::pair<u32, GetAddrInfoError> GetAddrInfoRequestImpl(HLERequestContext& ctx) {
    struct InputParameters {
        u8 use_nsd_resolve;
        u32 cancel_handle;
        u64 process_id;
    };
    static_assert(sizeof(InputParameters) == 0x10);

    IPC::RequestParser rp{ctx};
    const auto parameters = rp.PopRaw<InputParameters>();

    LOG_WARNING(
        Service,
        "called with ignored parameters: use_nsd_resolve={}, cancel_handle={}, process_id={}",
        parameters.use_nsd_resolve, parameters.cancel_handle, parameters.process_id);

    const auto host_buffer = ctx.ReadBuffer(0);
    std::string host = Common::StringFromBuffer(host_buffer);

    LOG_INFO(Service, "[Nextendo] DNS resolve (GetAddrInfo) requested: host={}", host);


    // [Nextendo] A literal IP has nothing to resolve -- return it as-is, before any of the
    // redirect/blocklist/NSD-rewrite logic below, all of which exist to turn a HOSTNAME into
    // the right address and have no business touching an address that's already one. See
    // TryParseIPv4Literal's declaration comment in internal_network/network.h for why falling
    // through to a real resolution here (which is what happened before this check existed) was
    // the actual root cause of Splatoon 3's NPLN connections completing TCP+TLS+HTTP/2 and then
    // silently closing without ever sending a HEADERS frame -- confirmed live: citron's own
    // connect cycles for this exact hostname resolved and connected correctly, but every one
    // still sent only a single small request and closed within ~1s of the server's reply, on
    // every cycle regardless of timing -- consistent with the game building an HTTP/2
    // :authority header from a corrupted canonical name, not any citron-side socket/scheduling
    // issue (both were separately investigated at length and ruled out).
    std::optional<std::string> service = std::nullopt;
    if (ctx.CanReadBuffer(1)) {
        const std::span<const u8> service_buffer = ctx.ReadBuffer(1);
        service = Common::StringFromBuffer(service_buffer);
    }

    if (Network::IPv4Address literal_ip; Network::TryParseIPv4Literal(host, literal_ip)) {
        u32 requested_type = 0;
        u32 requested_protocol = 0;
        if (ctx.CanReadBuffer(2)) {
            const auto hints = ctx.ReadBuffer(2);
            if (hints.size() >= 24) {
                std::array<u32_be, 6> header{};
                std::memcpy(header.data(), hints.data(), sizeof(header));
                if (header[0] == 0xBEEFCAFE) {
                    requested_type = header[3];
                    requested_protocol = header[4];
                }
            }
        }
        const bool udp = requested_type == static_cast<u32>(Type::DGRAM) ||
                         requested_protocol == static_cast<u32>(Protocol::UDP);
        u16 literal_port = 0;
        if (service.has_value() && !service->empty()) {
            const char* const first = service->data();
            const char* const last = first + service->size();
            const auto [end, ec] = std::from_chars(first, last, literal_port);
            if (ec != std::errc{} || end != last) {
                return {0, GetAddrInfoError::SERVICE};
            }
        }
        LOG_DEBUG(Service, "[Nextendo] Host '{}' is already a literal address: returned as-is",
                  host);
        Network::AddrInfo entry{};
        entry.family = Network::Domain::INET;
        entry.socket_type = udp ? Network::Type::DGRAM : Network::Type::STREAM;
        entry.protocol = udp ? Network::Protocol::UDP : Network::Protocol::TCP;
        entry.addr.family = Network::Domain::INET;
        entry.addr.ip = literal_ip;
        entry.addr.portno = literal_port;
        entry.canon_name = host;

        // Deliberately no SetLastHostForIp here, matching Ryujinx-Nextendo's own fix -- a
        // literal IP carries no hostname to record, and recording one would corrupt the
        // reverse lookup table used elsewhere for this exact purpose (see
        // GetLastHostForIp's declaration comment).
        const std::vector<u8> data = SerializeAddrInfo({entry}, host);
        const u32 data_size = static_cast<u32>(data.size());
        ctx.WriteBuffer(data, 0);
        return {data_size, GetAddrInfoError::SUCCESS};
    }

    if (parameters.use_nsd_resolve || host.find('%') != std::string::npos) {
        auto pos = host.find('%');
        if (pos != std::string::npos) {
            host.replace(pos, 1, "lp1");
        }
        if (host == "api.accounts.nintendo.com" || host == "accounts.nintendo.com") {
            host = "e0d67c509fb203858ebcb2fe3f88c2aa.baas.nintendo.com";
        }
        LOG_INFO(Service, "[sfdnsres] NSD resolved host to '{}'", host);
    }

    // [Nextendo] Redirect Nintendo hosts to the Nextendo servers when enabled.
    std::string query_host = host;
    auto redirect = GetNextendoRedirectIp(host);
    if (redirect.has_value()) {
        query_host = *redirect;
    } else if (IsBlockedHost(host)) {
        LOG_WARNING(Network, "Resolution of hostname {} requested, returning EAI_AGAIN", host);
        return {0, GetAddrInfoError::AGAIN};
    }

    auto res_v = Network::GetAddressInfo(query_host, service);
    auto* res = std::get_if<std::vector<Network::AddrInfo>>(&res_v);
    if (res == nullptr) {
        auto* err = std::get_if<Network::GetAddrInfoError>(&res_v);
        return {0, Translate(*err)};
    }

    if (redirect.has_value()) {
        // [Nextendo] Force the canonical name back to the real host: some titles
        // build their HTTP/2 :authority header from it.
        for (auto& addrinfo : *res) {
            addrinfo.canon_name = host;
        }

        // [Nextendo] Port-keyed recovery for gRPC-based titles (Splatoon 3).
        std::optional<u16> service_port;
        if (service.has_value()) {
            try {
                const int parsed = std::stoi(*service);
                if (parsed > 0 && parsed <= 0xFFFF) {
                    service_port = static_cast<u16>(parsed);
                }
            } catch (const std::exception&) {
            }
        }
        for (const auto& addrinfo : *res) {
            SetLastHostForIp(Network::IPv4AddressToString(addrinfo.addr.ip), host);
            if (service_port.has_value()) {
                SetLastIpForPort(*service_port, addrinfo.addr.ip);
            }
        }
    }

    // [Nextendo] GetAddrInfo doit renvoyer le format addrinfo (SerializeAddrInfo), pas hostent :
    // le format hostent est reserve a GetHostByName. Sinon le jeu ne parse pas la reponse.
    const std::vector<u8> data = SerializeAddrInfo(*res, host);
    const u32 data_size = static_cast<u32>(data.size());
    ctx.WriteBuffer(data, 0);

    return {data_size, GetAddrInfoError::SUCCESS};
}

void SFDNSRES::GetAddrInfoRequest(HLERequestContext& ctx) {
    auto [data_size, emu_gai_err] = GetAddrInfoRequestImpl(ctx);

    struct OutputParameters {
        Errno bsd_errno;
        GetAddrInfoError gai_error;
        u32 data_size;
    };
    static_assert(sizeof(OutputParameters) == 0xc);

    IPC::ResponseBuilder rb{ctx, 5};
    rb.Push(ResultSuccess);
    rb.PushRaw(OutputParameters{
        .bsd_errno = GetAddrInfoErrorToErrno(emu_gai_err),
        .gai_error = emu_gai_err,
        .data_size = data_size,
    });
}

void SFDNSRES::GetGaiStringErrorRequest(HLERequestContext& ctx) {
    struct InputParameters {
        GetAddrInfoError gai_errno;
    };
    IPC::RequestParser rp{ctx};
    auto input = rp.PopRaw<InputParameters>();

    const std::string result = Translate(input.gai_errno);
    ctx.WriteBuffer(result);

    IPC::ResponseBuilder rb{ctx, 2};
    rb.Push(ResultSuccess);
}

void SFDNSRES::GetAddrInfoRequestWithOptions(HLERequestContext& ctx) {
    // Additional options are ignored
    auto [data_size, emu_gai_err] = GetAddrInfoRequestImpl(ctx);

    struct OutputParameters {
        u32 data_size;
        GetAddrInfoError gai_error;
        NetDbError netdb_error;
        Errno bsd_errno;
    };
    static_assert(sizeof(OutputParameters) == 0x10);

    IPC::ResponseBuilder rb{ctx, 6};
    rb.Push(ResultSuccess);
    rb.PushRaw(OutputParameters{
        .data_size = data_size,
        .gai_error = emu_gai_err,
        .netdb_error = GetAddrInfoErrorToNetDbError(emu_gai_err),
        .bsd_errno = GetAddrInfoErrorToErrno(emu_gai_err),
    });
}

void SFDNSRES::ResolverSetOptionRequest(HLERequestContext& ctx) {
    LOG_WARNING(Service, "(STUBBED) called");

    IPC::ResponseBuilder rb{ctx, 3};

    rb.Push(ResultSuccess);
    rb.Push<s32>(0); // bsd errno
}


DNS_PRIV::DNS_PRIV(Core::System& system_)
    : ServiceFramework{system_, "dns:priv"} {
    // clang-format off
    static const FunctionInfo functions[] = {
        {0, nullptr, "Cmd0"},
        {1, nullptr, "Cmd1"},
        {2, nullptr, "Cmd2"},
    };
    // clang-format on
    RegisterHandlers(functions);
}

DNS_PRIV::~DNS_PRIV() = default;


} // namespace Service::Sockets
