#include "hypernova/protocol.hpp"

#include <arpa/inet.h>
#include <cerrno>
#include <chrono>
#include <csignal>
#include <cstdint>
#include <cstring>
#include <deque>
#include <fcntl.h>
#include <iostream>
#include <limits>
#include <optional>
#include <poll.h>
#include <cstdio>
#include <string>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace {

using Clock = std::chrono::steady_clock;
using Milliseconds = std::chrono::milliseconds;

constexpr const char* kDefaultTcAddress = "192.168.0.30";
constexpr std::uint16_t kDefaultTcPort = 6001;
constexpr std::uint16_t kDefaultAndroidPort = 6100;
constexpr std::uint16_t kDefaultTelemetryPort = 6000;
constexpr Milliseconds kReconnectDelay{1000};
constexpr Milliseconds kTcQuarantine{750};
constexpr Milliseconds kCommandTimeout{5000};
constexpr Milliseconds kTelemetryFresh{3000};
constexpr Milliseconds kStateThrottle{200};
constexpr Milliseconds kStateHeartbeat{1000};

// ---------------------------------------------------------------------------
// Digital cluster bottom bar
//
// The QNX cluster app reads its bottom-bar values as one bare number per file
// under /tmp/ivi (Qnx-Cluster/src/Backend/BottomBar/BottomBarDataProvider.cpp).
// Nothing wrote those files, so the bottom bar always showed each provider's
// hardcoded default. This service already holds the only decoded copy of
// TC397's sensor frame, so it is the right place to publish them.
//
// SCOPE, DELIBERATELY: TC397's sensor frame carries exactly THREE signals —
// temperature, humidity, fuel. So only two cluster files have a real source:
//     /tmp/ivi/fuel.txt      <- TC397 fuel
//     /tmp/ivi/env_temp.txt  <- TC397 temperature (ambient)
// engine_temp.txt and total_kms.txt have NO TC397 signal behind them and are
// deliberately NOT written here — inventing a plausible number for a gauge
// that claims to be a real sensor reading is worse than showing a static
// default, and it would be indistinguishable from a working sensor on the
// bench. They keep their provider defaults until a real source exists.
// ---------------------------------------------------------------------------
constexpr const char* kDefaultClusterDir = "/tmp/ivi";
constexpr Milliseconds kClusterThrottle{100};   // <= cluster polls at 20 Hz

constexpr std::uint8_t kStatusAccepted = 1;
constexpr std::uint8_t kStatusConfirmed = 2;
constexpr std::uint8_t kStatusRejected = 3;
constexpr std::uint8_t kStatusUnavailable = 4;
constexpr std::uint8_t kStatusTimeout = 5;

constexpr std::uint8_t kLocalBusy = 0xe1;
constexpr std::uint8_t kLocalUnavailable = 0xe2;
constexpr std::uint8_t kLocalInvalid = 0xe3;
constexpr std::uint8_t kLocalTimeout = 0xe4;

volatile std::sig_atomic_t g_stop = 0;

void on_signal(int) {
    g_stop = 1;
}

void close_fd(int& fd) {
    if (fd >= 0) {
        ::close(fd);
        fd = -1;
    }
}

bool set_nonblocking(int fd) {
    const int flags = ::fcntl(fd, F_GETFL, 0);
    return flags >= 0 && ::fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0;
}

std::uint16_t parse_port(const std::string& value) {
    const auto parsed = std::stoul(value);
    if (parsed == 0 || parsed > 65535) throw std::invalid_argument("port out of range");
    return static_cast<std::uint16_t>(parsed);
}

struct Config {
    std::string tc_address{kDefaultTcAddress};
    std::uint16_t tc_port{kDefaultTcPort};
    std::uint16_t android_port{kDefaultAndroidPort};
    std::uint16_t telemetry_port{kDefaultTelemetryPort};
    std::string cluster_dir{kDefaultClusterDir};
    bool cluster_files{true};
};

// Write one bare number, the format the cluster's providers parse with
// `ifstream >> value`. Temp file + rename so a reader polling at 20 Hz can
// never catch a partially written file. Both are local-filesystem calls, so
// this is unaffected by the board's SFTP server lacking rename support.
bool write_scalar_file(const std::string& path, double value) {
    const std::string tmp = path + ".tmp";
    std::FILE* handle = std::fopen(tmp.c_str(), "w");
    if (handle == nullptr) return false;
    const int written = std::fprintf(handle, "%.1f\n", value);
    if (std::fclose(handle) != 0 || written < 0) {
        (void)std::remove(tmp.c_str());
        return false;
    }
    if (std::rename(tmp.c_str(), path.c_str()) != 0) {
        (void)std::remove(tmp.c_str());
        return false;
    }
    return true;
}

Config parse_args(int argc, char** argv) {
    Config config;
    for (int index = 1; index < argc; ++index) {
        const std::string option = argv[index];
        if (index + 1 >= argc) throw std::invalid_argument("missing value for " + option);
        const std::string value = argv[++index];
        if (option == "--tc-address") config.tc_address = value;
        else if (option == "--tc-port") config.tc_port = parse_port(value);
        else if (option == "--android-port") config.android_port = parse_port(value);
        else if (option == "--telemetry-port") config.telemetry_port = parse_port(value);
        else if (option == "--cluster-dir") config.cluster_dir = value;
        else if (option == "--cluster-files") {
            // Takes an explicit on/off value rather than being a bare flag,
            // because this parser requires a value for every option.
            if (value == "on") config.cluster_files = true;
            else if (value == "off") config.cluster_files = false;
            else throw std::invalid_argument("--cluster-files expects on|off");
        }
        else throw std::invalid_argument("unknown option " + option);
    }
    return config;
}

int create_listener(std::uint16_t port) {
    int fd = ::socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    const int one = 1;
    (void)::setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons(port);
    if (::bind(fd, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) != 0 ||
        ::listen(fd, 4) != 0 || !set_nonblocking(fd)) {
        close_fd(fd);
    }
    return fd;
}

int create_udp_receiver(std::uint16_t port) {
    int fd = ::socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return -1;
    const int one = 1;
    (void)::setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons(port);
    if (::bind(fd, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) != 0 ||
        !set_nonblocking(fd)) {
        close_fd(fd);
    }
    return fd;
}

struct ConnectAttempt {
    int fd{-1};
    bool connected{false};
};

ConnectAttempt connect_tc(const Config& config) {
    ConnectAttempt result;
    result.fd = ::socket(AF_INET, SOCK_STREAM, 0);
    if (result.fd < 0 || !set_nonblocking(result.fd)) {
        close_fd(result.fd);
        return result;
    }
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(config.tc_port);
    if (::inet_pton(AF_INET, config.tc_address.c_str(), &address.sin_addr) != 1) {
        close_fd(result.fd);
        return result;
    }
    const int status = ::connect(
        result.fd,
        reinterpret_cast<const sockaddr*>(&address),
        sizeof(address)
    );
    if (status == 0) result.connected = true;
    else if (errno != EINPROGRESS) close_fd(result.fd);
    return result;
}

class WriteQueue {
public:
    void push(std::vector<std::uint8_t> bytes) {
        if (frames_.size() >= 128) {
            std::cerr << "output queue limit reached; closing peer\n";
            overflowed_ = true;
            return;
        }
        frames_.push_back(std::move(bytes));
    }

    bool empty() const { return frames_.empty(); }
    bool overflowed() const { return overflowed_; }
    void clear() { frames_.clear(); offset_ = 0; overflowed_ = false; }

    bool flush(int fd) {
        while (!frames_.empty()) {
            const auto& frame = frames_.front();
            const auto remaining = frame.size() - offset_;
            int flags = 0;
#ifdef MSG_NOSIGNAL
            flags = MSG_NOSIGNAL;
#endif
            const auto written = ::send(fd, frame.data() + offset_, remaining, flags);
            if (written > 0) {
                offset_ += static_cast<std::size_t>(written);
                if (offset_ == frame.size()) {
                    frames_.pop_front();
                    offset_ = 0;
                }
                continue;
            }
            if (written < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return true;
            if (written < 0 && errno == EINTR) continue;
            return false;
        }
        return true;
    }

private:
    std::deque<std::vector<std::uint8_t>> frames_;
    std::size_t offset_{0};
    bool overflowed_{false};
};

struct PendingCommand {
    std::uint32_t correlation{};
    std::uint8_t tc_sequence{};
    std::uint8_t target{};
    std::uint8_t fan{};
    std::uint8_t zone{};
    std::uint8_t caller{};
    Clock::time_point deadline{};
};

struct VehicleState {
    int temperature{-1};
    int humidity{-1};
    int fuel{-1};
    int zone1_target{-1};
    int zone2_target{-1};
    int zone1_fan{-1};
    int zone2_fan{-1};
    std::uint8_t dtc_mask{0};
    std::uint8_t last_tc_event_sequence{0};
    bool has_telemetry{false};
    Clock::time_point telemetry_at{};
};

void append_u32_be(std::vector<std::uint8_t>& out, std::uint32_t value) {
    out.push_back(static_cast<std::uint8_t>((value >> 24u) & 0xffu));
    out.push_back(static_cast<std::uint8_t>((value >> 16u) & 0xffu));
    out.push_back(static_cast<std::uint8_t>((value >> 8u) & 0xffu));
    out.push_back(static_cast<std::uint8_t>(value & 0xffu));
}

std::uint8_t scalar_byte(int value) {
    return value < 0 ? 0xffu : static_cast<std::uint8_t>(value);
}

std::uint8_t dtc_bit(std::uint16_t dtc) {
    switch (dtc) {
        case 0x0217: return 1u << 0u;
        case 0x0118: return 1u << 1u;
        case 0x0300: return 1u << 2u;
        case 0x0442: return 1u << 3u;
        case 0x0562: return 1u << 4u;
        default: return 0;
    }
}

class Gateway {
public:
    explicit Gateway(Config config) : config_(std::move(config)) {}

    int run() {
        listener_fd_ = create_listener(config_.android_port);
        udp_fd_ = create_udp_receiver(config_.telemetry_port);
        if (listener_fd_ < 0 || udp_fd_ < 0) {
            std::cerr << "cannot bind Android TCP :" << config_.android_port
                      << " or telemetry UDP :" << config_.telemetry_port << "\n";
            return 1;
        }

        std::cout << "HyperNova gateway: Android TCP :" << config_.android_port
                  << ", TC397 " << config_.tc_address << ':' << config_.tc_port
                  << ", telemetry UDP :" << config_.telemetry_port << "\n";
        next_tc_connect_ = Clock::now();

        while (g_stop == 0) {
            const auto now = Clock::now();
            maybe_start_tc_connect(now);
            expire_command(now);
            publish_periodic_state(now);
            publish_cluster_files(now);

            pollfd descriptors[4]{};
            descriptors[0] = {listener_fd_, POLLIN, 0};
            descriptors[1] = {udp_fd_, POLLIN, 0};
            descriptors[2] = {
                android_fd_,
                static_cast<short>(POLLIN | (android_out_.empty() ? 0 : POLLOUT)),
                0
            };
            descriptors[3] = {
                tc_fd_,
                static_cast<short>(POLLIN | ((tc_connecting_ || !tc_out_.empty()) ? POLLOUT : 0)),
                0
            };

            const int ready = ::poll(descriptors, 4, 100);
            if (ready < 0) {
                if (errno == EINTR) continue;
                std::cerr << "poll failed: " << std::strerror(errno) << "\n";
                break;
            }

            if ((descriptors[0].revents & POLLIN) != 0) accept_android();
            if ((descriptors[1].revents & POLLIN) != 0) read_udp();
            handle_android_events(descriptors[2].revents);
            handle_tc_events(descriptors[3].revents);
        }

        close_android();
        close_tc("shutdown");
        close_fd(udp_fd_);
        close_fd(listener_fd_);
        return 0;
    }

private:
    void maybe_start_tc_connect(Clock::time_point now) {
        if (tc_fd_ >= 0 || now < next_tc_connect_) return;
        auto attempt = connect_tc(config_);
        tc_fd_ = attempt.fd;
        tc_connecting_ = tc_fd_ >= 0 && !attempt.connected;
        if (attempt.connected) tc_connected(now);
        else if (tc_fd_ < 0) next_tc_connect_ = now + kReconnectDelay;
    }

    void tc_connected(Clock::time_point now) {
        tc_connecting_ = false;
        tc_connected_at_ = now;
        std::cout << "TC397 connected; draining retained events for "
                  << kTcQuarantine.count() << " ms\n";
        state_dirty_ = true;
    }

    void close_tc(const char* reason) {
        if (tc_fd_ >= 0) std::cerr << "TC397 disconnected: " << reason << "\n";
        close_fd(tc_fd_);
        tc_connecting_ = false;
        tc_input_.clear();
        tc_out_.clear();
        next_tc_connect_ = Clock::now() + kReconnectDelay;
        if (pending_) {
            send_command_result(*pending_, kStatusUnavailable, kLocalUnavailable);
            pending_.reset();
        }
        state_dirty_ = true;
    }

    void accept_android() {
        sockaddr_in peer{};
        socklen_t peer_size = sizeof(peer);
        const int accepted = ::accept(
            listener_fd_, reinterpret_cast<sockaddr*>(&peer), &peer_size
        );
        if (accepted < 0) return;
        if (android_fd_ >= 0 || !set_nonblocking(accepted)) {
            ::close(accepted);
            return;
        }
        android_fd_ = accepted;
        android_ready_ = false;
        android_input_.clear();
        android_out_.clear();
        std::cout << "Android gateway client connected\n";
    }

    void close_android() {
        if (android_fd_ >= 0) std::cerr << "Android gateway client disconnected\n";
        close_fd(android_fd_);
        android_ready_ = false;
        android_input_.clear();
        android_out_.clear();
    }

    static bool read_stream(int fd, std::vector<std::uint8_t>& input) {
        std::uint8_t chunk[2048];
        while (true) {
            const auto count = ::recv(fd, chunk, sizeof(chunk), 0);
            if (count > 0) {
                input.insert(input.end(), chunk, chunk + count);
                if (input.size() > 8192) return false;
                continue;
            }
            if (count == 0) return false;
            if (errno == EAGAIN || errno == EWOULDBLOCK) return true;
            if (errno == EINTR) continue;
            return false;
        }
    }

    void handle_android_events(short events) {
        if (android_fd_ < 0 || events == 0) return;
        if ((events & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
            close_android();
            return;
        }
        if ((events & POLLIN) != 0) {
            if (!read_stream(android_fd_, android_input_) || !parse_android()) {
                close_android();
                return;
            }
        }
        if ((events & POLLOUT) != 0 &&
            (!android_out_.flush(android_fd_) || android_out_.overflowed())) {
            close_android();
        }
    }

    bool parse_android() {
        while (!android_input_.empty()) {
            const auto decoded = hypernova::decode_gateway(
                android_input_.data(), android_input_.size()
            );
            if (decoded.status == hypernova::DecodeStatus::incomplete) return true;
            if (decoded.status == hypernova::DecodeStatus::invalid) {
                std::cerr << "invalid Android frame: " << decoded.error << "\n";
                return false;
            }
            android_input_.erase(
                android_input_.begin(),
                android_input_.begin() + static_cast<std::ptrdiff_t>(decoded.consumed)
            );
            if (!handle_android_frame(decoded.frame)) return false;
        }
        return true;
    }

    bool handle_android_frame(const hypernova::GatewayFrame& frame) {
        if (frame.type == hypernova::kGatewayHello) {
            if (frame.correlation_id != 0 || frame.payload.size() != 6 ||
                frame.payload[0] != 0 || frame.payload[1] != hypernova::kGatewayVersion) {
                return false;
            }
            android_ready_ = true;
            android_out_.push(hypernova::encode_gateway(
                hypernova::kGatewayHelloAck, 0, {0, hypernova::kGatewayVersion, 0, 1}
            ));
            queue_state(true);
            return true;
        }
        if (!android_ready_) return false;
        if (frame.type == hypernova::kGatewayPing && frame.payload.empty()) {
            android_out_.push(hypernova::encode_gateway(
                hypernova::kGatewayPong, frame.correlation_id, {}
            ));
            return true;
        }
        if (frame.type == hypernova::kGatewayGetState && frame.payload.empty()) {
            queue_state(true);
            return true;
        }
        if (frame.type == hypernova::kGatewaySetHvac) {
            handle_set_hvac(frame);
            return true;
        }
        return false;
    }

    void handle_set_hvac(const hypernova::GatewayFrame& frame) {
        PendingCommand command;
        command.correlation = frame.correlation_id;
        if (frame.payload.size() == 4) {
            command.target = frame.payload[0];
            command.fan = frame.payload[1];
            command.zone = frame.payload[2];
            command.caller = frame.payload[3];
        }
        const bool valid = frame.correlation_id != 0 && frame.payload.size() == 4 &&
            command.fan <= 5 && (command.fan == 0 ||
            (command.target >= 16 && command.target <= 28)) &&
            command.zone <= 2 && command.caller <= 1;
        if (!valid) {
            send_command_result(command, kStatusRejected, kLocalInvalid);
            return;
        }
        if (tc_fd_ < 0 || tc_connecting_ || Clock::now() - tc_connected_at_ < kTcQuarantine) {
            send_command_result(command, kStatusUnavailable, kLocalUnavailable);
            return;
        }
        if (pending_) {
            send_command_result(command, kStatusRejected, kLocalBusy);
            return;
        }

        command.tc_sequence = next_tc_sequence_++;
        command.deadline = Clock::now() + kCommandTimeout;
        tc_out_.push(hypernova::encode_tc(
            hypernova::kTcSetHvac,
            command.tc_sequence,
            {command.target, command.fan, command.zone, command.caller}
        ));
        pending_ = command;
        send_command_result(command, kStatusAccepted, 0);
    }

    void send_command_result(
        const PendingCommand& command,
        std::uint8_t status,
        std::uint8_t reason
    ) {
        if (!android_ready_) return;
        const std::vector<std::uint8_t> payload{
            hypernova::kGatewaySetHvac,
            status,
            reason,
            0,
            command.target,
            command.fan,
            command.zone,
            command.caller,
            command.tc_sequence,
            0, 0, 0
        };
        android_out_.push(hypernova::encode_gateway(
            hypernova::kGatewayCommandResult, command.correlation, payload
        ));
    }

    void handle_tc_events(short events) {
        if (tc_fd_ < 0 || events == 0) return;
        if (tc_connecting_ && (events & (POLLOUT | POLLERR | POLLHUP)) != 0) {
            int error = 0;
            socklen_t size = sizeof(error);
            if (::getsockopt(tc_fd_, SOL_SOCKET, SO_ERROR, &error, &size) != 0 || error != 0) {
                close_tc(error == 0 ? "connect failed" : std::strerror(error));
                return;
            }
            tc_connected(Clock::now());
        }
        if ((events & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
            close_tc("socket error");
            return;
        }
        if ((events & POLLIN) != 0) {
            if (!read_stream(tc_fd_, tc_input_)) {
                close_tc("peer closed");
                return;
            }
            parse_tc_stream();
        }
        if (!tc_connecting_ && (events & POLLOUT) != 0 &&
            (!tc_out_.flush(tc_fd_) || tc_out_.overflowed())) {
            close_tc("write failed");
        }
    }

    void parse_tc_stream() {
        while (!tc_input_.empty()) {
            const auto decoded = hypernova::decode_tc(tc_input_.data(), tc_input_.size());
            if (decoded.status == hypernova::DecodeStatus::incomplete) return;
            tc_input_.erase(
                tc_input_.begin(),
                tc_input_.begin() + static_cast<std::ptrdiff_t>(decoded.consumed)
            );
            if (!decoded.frame.crc_ok) {
                std::cerr << "dropping TC397 frame with bad CRC\n";
                continue;
            }
            handle_tc_frame(decoded.frame);
        }
    }

    void read_udp() {
        std::uint8_t datagram[512];
        while (true) {
            const auto count = ::recvfrom(udp_fd_, datagram, sizeof(datagram), 0, nullptr, nullptr);
            if (count < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK) return;
                if (errno == EINTR) continue;
                return;
            }
            const auto decoded = hypernova::decode_tc(
                datagram, static_cast<std::size_t>(count)
            );
            if (decoded.status == hypernova::DecodeStatus::complete &&
                decoded.consumed == static_cast<std::size_t>(count) && decoded.frame.crc_ok) {
                handle_tc_frame(decoded.frame);
            }
        }
    }

    void handle_tc_frame(const hypernova::TcFrame& frame) {
        state_.last_tc_event_sequence = frame.sequence;
        // The final TC397 contract carries exactly three sensor bytes. Tolerate
        // the already-flashed four-byte demo image until it is reflashed; byte
        // four is intentionally ignored and never becomes a vehicle signal.
        if (frame.type == hypernova::kTcSensorData &&
            (frame.payload.size() == 3 || frame.payload.size() == 4)) {
            state_.temperature = static_cast<std::int8_t>(frame.payload[0]);
            state_.humidity = frame.payload[1];
            state_.fuel = frame.payload[2];
            state_.has_telemetry = true;
            state_.telemetry_at = Clock::now();
            state_dirty_ = true;
            return;
        }
        if (frame.type == hypernova::kTcFaultEvent && frame.payload.size() == 3) {
            const auto dtc = static_cast<std::uint16_t>(
                (static_cast<std::uint16_t>(frame.payload[0]) << 8u) | frame.payload[1]
            );
            const auto bit = dtc_bit(dtc);
            const bool active = frame.payload[2] == 1;
            if (bit != 0) {
                if (active) state_.dtc_mask |= bit;
                else state_.dtc_mask &= static_cast<std::uint8_t>(~bit);
            }
            if (android_ready_) {
                android_out_.push(hypernova::encode_gateway(
                    hypernova::kGatewayFaultEvent,
                    0,
                    {frame.payload[0], frame.payload[1], frame.payload[2], frame.sequence}
                ));
            }
            state_dirty_ = true;
            return;
        }
        if (!pending_ || frame.sequence != pending_->tc_sequence) return;
        if (frame.type == hypernova::kTcCommandAck && frame.payload.size() == 1 &&
            frame.payload[0] == hypernova::kTcSetHvac) {
            apply_confirmed_hvac(*pending_);
            send_command_result(*pending_, kStatusConfirmed, 0);
            pending_.reset();
            state_dirty_ = true;
        } else if (frame.type == hypernova::kTcCommandRejected && frame.payload.size() == 2 &&
                   frame.payload[0] == hypernova::kTcSetHvac) {
            send_command_result(*pending_, kStatusRejected, frame.payload[1]);
            pending_.reset();
        }
    }

    void apply_confirmed_hvac(const PendingCommand& command) {
        if (command.zone == 0 || command.zone == 1) {
            if (command.fan > 0) state_.zone1_target = command.target;
            state_.zone1_fan = command.fan;
        }
        if (command.zone == 0 || command.zone == 2) {
            if (command.fan > 0) state_.zone2_target = command.target;
            state_.zone2_fan = command.fan;
        }
    }

    void expire_command(Clock::time_point now) {
        if (pending_ && now >= pending_->deadline) {
            send_command_result(*pending_, kStatusTimeout, kLocalTimeout);
            pending_.reset();
        }
    }

    void publish_periodic_state(Clock::time_point now) {
        if (!android_ready_) return;
        if ((state_dirty_ && now - last_state_sent_ >= kStateThrottle) ||
            now - last_state_sent_ >= kStateHeartbeat) {
            queue_state(false);
        }
    }

    // Publish TC397 sensor values to the digital cluster's bottom-bar files.
    //
    // Deliberately NOT gated on `android_ready_` (unlike publish_periodic_state
    // above): the cluster is a separate consumer and must keep updating whether
    // or not an Android app happens to be connected. Tying the two together
    // would have made the bottom bar go stale the moment Android disconnected,
    // which is precisely when a driver still needs to see fuel.
    void publish_cluster_files(Clock::time_point now) {
        if (!config_.cluster_files) return;
        if (!state_.has_telemetry) return;   // nothing real to publish yet
        if (now - last_cluster_write_ < kClusterThrottle) return;
        last_cluster_write_ = now;

        if (!cluster_dir_ready_) {
            // 0755; EEXIST is success. If the directory cannot be created there
            // is no point retrying every 100 ms forever, so report once and
            // disable — a broken bottom bar must not become a log flood.
            if (::mkdir(config_.cluster_dir.c_str(), 0755) != 0 && errno != EEXIST) {
                std::cerr << "cluster files disabled: cannot create "
                          << config_.cluster_dir << ": " << std::strerror(errno) << "\n";
                config_.cluster_files = false;
                return;
            }
            cluster_dir_ready_ = true;
        }

        // Write only on change: the cluster re-reads these at 20 Hz and TC397
        // emits at ~12 Hz, so rewriting unchanged values is pure churn.
        if (state_.fuel != last_published_fuel_) {
            if (write_scalar_file(config_.cluster_dir + "/fuel.txt", state_.fuel)) {
                last_published_fuel_ = state_.fuel;
            }
        }
        if (state_.temperature != last_published_temperature_) {
            if (write_scalar_file(config_.cluster_dir + "/env_temp.txt", state_.temperature)) {
                last_published_temperature_ = state_.temperature;
            }
        }
    }

    void queue_state(bool immediate) {
        if (!android_ready_) return;
        const auto now = Clock::now();
        std::uint32_t age = std::numeric_limits<std::uint32_t>::max();
        bool fresh = false;
        if (state_.has_telemetry) {
            const auto elapsed = std::chrono::duration_cast<Milliseconds>(
                now - state_.telemetry_at
            ).count();
            const auto bounded = elapsed < 0 ? 0 : elapsed;
            age = bounded > static_cast<long long>(std::numeric_limits<std::uint32_t>::max())
                ? std::numeric_limits<std::uint32_t>::max()
                : static_cast<std::uint32_t>(bounded);
            fresh = age < static_cast<std::uint32_t>(kTelemetryFresh.count());
        }
        std::uint8_t flags = 0;
        if (tc_fd_ >= 0 && !tc_connecting_) flags |= 1u;
        if (fresh) flags |= 2u;
        std::vector<std::uint8_t> payload{
            scalar_byte(state_.temperature),
            scalar_byte(state_.humidity),
            scalar_byte(state_.fuel),
            scalar_byte(state_.zone1_target),
            scalar_byte(state_.zone2_target),
            scalar_byte(state_.zone1_fan),
            scalar_byte(state_.zone2_fan),
            state_.dtc_mask,
            flags
        };
        append_u32_be(payload, age);
        payload.push_back(state_.last_tc_event_sequence);
        android_out_.push(hypernova::encode_gateway(
            hypernova::kGatewayVehicleState, 0, payload
        ));
        state_dirty_ = false;
        if (immediate || last_state_sent_ == Clock::time_point{}) last_state_sent_ = now;
        else last_state_sent_ = now;
    }

    Config config_;
    int listener_fd_{-1};
    int udp_fd_{-1};
    int android_fd_{-1};
    int tc_fd_{-1};
    bool android_ready_{false};
    bool tc_connecting_{false};
    std::vector<std::uint8_t> android_input_;
    std::vector<std::uint8_t> tc_input_;
    WriteQueue android_out_;
    WriteQueue tc_out_;
    std::optional<PendingCommand> pending_;
    VehicleState state_;
    std::uint8_t next_tc_sequence_{1};
    bool state_dirty_{true};
    // Cluster bottom-bar publishing. The "last published" values start at
    // sentinels no real reading can equal, so the first genuine sample always
    // writes.
    Clock::time_point last_cluster_write_{};
    bool cluster_dir_ready_{false};
    int last_published_fuel_{-1};
    int last_published_temperature_{-1000};
    Clock::time_point tc_connected_at_{};
    Clock::time_point next_tc_connect_{};
    Clock::time_point last_state_sent_{};
};

}  // namespace

int main(int argc, char** argv) {
    std::signal(SIGINT, on_signal);
    std::signal(SIGTERM, on_signal);
#ifdef SIGPIPE
    std::signal(SIGPIPE, SIG_IGN);
#endif
    try {
        Gateway gateway(parse_args(argc, argv));
        return gateway.run();
    } catch (const std::exception& error) {
        std::cerr << "configuration error: " << error.what() << "\n";
        return 2;
    }
}
