#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace hypernova {

constexpr std::size_t kTcHeaderSize = 3;
constexpr std::size_t kTcCrcSize = 2;
constexpr std::size_t kTcMaxPayload = 250;

constexpr std::uint8_t kTcSetHvac = 0x01;
constexpr std::uint8_t kTcRequestSensors = 0x10;
constexpr std::uint8_t kTcSensorData = 0x80;
constexpr std::uint8_t kTcFaultEvent = 0x81;
constexpr std::uint8_t kTcCommandRejected = 0x82;
constexpr std::uint8_t kTcCommandAck = 0x83;

constexpr std::size_t kGatewayHeaderSize = 16;
constexpr std::size_t kGatewayMaxPayload = 512;
constexpr std::uint8_t kGatewayVersion = 1;

constexpr std::uint8_t kGatewayHello = 0x01;
constexpr std::uint8_t kGatewayPing = 0x02;
constexpr std::uint8_t kGatewaySetHvac = 0x10;
constexpr std::uint8_t kGatewayGetState = 0x20;
constexpr std::uint8_t kGatewayHelloAck = 0x81;
constexpr std::uint8_t kGatewayPong = 0x82;
constexpr std::uint8_t kGatewayCommandResult = 0x90;
constexpr std::uint8_t kGatewayVehicleState = 0xA0;
constexpr std::uint8_t kGatewayFaultEvent = 0xA1;

struct TcFrame {
    std::uint8_t type{};
    std::uint8_t sequence{};
    std::vector<std::uint8_t> payload;
    bool crc_ok{};
};

struct GatewayFrame {
    std::uint8_t type{};
    std::uint32_t correlation_id{};
    std::vector<std::uint8_t> payload;
};

enum class DecodeStatus {
    incomplete,
    complete,
    invalid,
};

struct TcDecodeResult {
    DecodeStatus status{DecodeStatus::incomplete};
    TcFrame frame;
    std::size_t consumed{};
    std::string error;
};

struct GatewayDecodeResult {
    DecodeStatus status{DecodeStatus::incomplete};
    GatewayFrame frame;
    std::size_t consumed{};
    std::string error;
};

std::uint16_t crc16_ccitt(const std::uint8_t* data, std::size_t length);

std::vector<std::uint8_t> encode_tc(
    std::uint8_t type,
    std::uint8_t sequence,
    const std::vector<std::uint8_t>& payload
);

TcDecodeResult decode_tc(const std::uint8_t* data, std::size_t length);

std::vector<std::uint8_t> encode_gateway(
    std::uint8_t type,
    std::uint32_t correlation_id,
    const std::vector<std::uint8_t>& payload
);

GatewayDecodeResult decode_gateway(const std::uint8_t* data, std::size_t length);

}  // namespace hypernova
