#include "hypernova/protocol.hpp"

#include <algorithm>
#include <array>
#include <stdexcept>

namespace hypernova {
namespace {

constexpr std::array<std::uint8_t, 4> kMagic{'H', 'N', 'V', 'G'};

std::uint16_t read_u16_be(const std::uint8_t* data) {
    return static_cast<std::uint16_t>(
        (static_cast<std::uint16_t>(data[0]) << 8u) |
        static_cast<std::uint16_t>(data[1])
    );
}

std::uint32_t read_u32_be(const std::uint8_t* data) {
    return (static_cast<std::uint32_t>(data[0]) << 24u) |
           (static_cast<std::uint32_t>(data[1]) << 16u) |
           (static_cast<std::uint32_t>(data[2]) << 8u) |
           static_cast<std::uint32_t>(data[3]);
}

void append_u16_be(std::vector<std::uint8_t>& out, std::uint16_t value) {
    out.push_back(static_cast<std::uint8_t>((value >> 8u) & 0xffu));
    out.push_back(static_cast<std::uint8_t>(value & 0xffu));
}

void append_u32_be(std::vector<std::uint8_t>& out, std::uint32_t value) {
    out.push_back(static_cast<std::uint8_t>((value >> 24u) & 0xffu));
    out.push_back(static_cast<std::uint8_t>((value >> 16u) & 0xffu));
    out.push_back(static_cast<std::uint8_t>((value >> 8u) & 0xffu));
    out.push_back(static_cast<std::uint8_t>(value & 0xffu));
}

}  // namespace

std::uint16_t crc16_ccitt(const std::uint8_t* data, std::size_t length) {
    std::uint16_t crc = 0xffffu;
    for (std::size_t index = 0; index < length; ++index) {
        crc ^= static_cast<std::uint16_t>(data[index]) << 8u;
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 0x8000u) != 0u
                ? static_cast<std::uint16_t>((crc << 1u) ^ 0x1021u)
                : static_cast<std::uint16_t>(crc << 1u);
        }
    }
    return crc;
}

std::vector<std::uint8_t> encode_tc(
    std::uint8_t type,
    std::uint8_t sequence,
    const std::vector<std::uint8_t>& payload
) {
    if (payload.size() > kTcMaxPayload) {
        throw std::invalid_argument("TC397 payload exceeds 250 bytes");
    }

    std::vector<std::uint8_t> crc_input;
    crc_input.reserve(1u + payload.size());
    crc_input.push_back(type);
    crc_input.insert(crc_input.end(), payload.begin(), payload.end());
    const auto crc = crc16_ccitt(crc_input.data(), crc_input.size());

    std::vector<std::uint8_t> out;
    out.reserve(kTcHeaderSize + payload.size() + kTcCrcSize);
    out.push_back(type);
    out.push_back(sequence);
    out.push_back(static_cast<std::uint8_t>(payload.size()));
    out.insert(out.end(), payload.begin(), payload.end());
    out.push_back(static_cast<std::uint8_t>(crc & 0xffu));
    out.push_back(static_cast<std::uint8_t>((crc >> 8u) & 0xffu));
    return out;
}

TcDecodeResult decode_tc(const std::uint8_t* data, std::size_t length) {
    if (length < kTcHeaderSize) return {};
    const auto payload_length = static_cast<std::size_t>(data[2]);
    const auto total = kTcHeaderSize + payload_length + kTcCrcSize;
    if (length < total) return {};

    TcDecodeResult result;
    result.status = DecodeStatus::complete;
    result.consumed = total;
    result.frame.type = data[0];
    result.frame.sequence = data[1];
    result.frame.payload.assign(data + kTcHeaderSize, data + kTcHeaderSize + payload_length);

    std::vector<std::uint8_t> crc_input;
    crc_input.reserve(1u + payload_length);
    crc_input.push_back(result.frame.type);
    crc_input.insert(crc_input.end(), result.frame.payload.begin(), result.frame.payload.end());
    const auto expected = crc16_ccitt(crc_input.data(), crc_input.size());
    const auto received = static_cast<std::uint16_t>(
        static_cast<std::uint16_t>(data[kTcHeaderSize + payload_length]) |
        (static_cast<std::uint16_t>(data[kTcHeaderSize + payload_length + 1]) << 8u)
    );
    result.frame.crc_ok = expected == received;
    if (!result.frame.crc_ok) result.error = "TC397 CRC mismatch";
    return result;
}

std::vector<std::uint8_t> encode_gateway(
    std::uint8_t type,
    std::uint32_t correlation_id,
    const std::vector<std::uint8_t>& payload
) {
    if (payload.size() > kGatewayMaxPayload) {
        throw std::invalid_argument("HNVG payload exceeds 512 bytes");
    }
    std::vector<std::uint8_t> out;
    out.reserve(kGatewayHeaderSize + payload.size());
    out.insert(out.end(), kMagic.begin(), kMagic.end());
    out.push_back(kGatewayVersion);
    out.push_back(type);
    append_u16_be(out, 0u);
    append_u32_be(out, correlation_id);
    append_u16_be(out, static_cast<std::uint16_t>(payload.size()));
    append_u16_be(out, 0u);
    out.insert(out.end(), payload.begin(), payload.end());
    return out;
}

GatewayDecodeResult decode_gateway(const std::uint8_t* data, std::size_t length) {
    if (length < kGatewayHeaderSize) return {};
    GatewayDecodeResult result;
    if (!std::equal(kMagic.begin(), kMagic.end(), data)) {
        result.status = DecodeStatus::invalid;
        result.error = "invalid HNVG magic";
        return result;
    }
    if (data[4] != kGatewayVersion) {
        result.status = DecodeStatus::invalid;
        result.error = "unsupported HNVG version";
        return result;
    }
    if (read_u16_be(data + 6) != 0u || read_u16_be(data + 14) != 0u) {
        result.status = DecodeStatus::invalid;
        result.error = "non-zero HNVG flags/reserved";
        return result;
    }
    const auto payload_length = static_cast<std::size_t>(read_u16_be(data + 12));
    if (payload_length > kGatewayMaxPayload) {
        result.status = DecodeStatus::invalid;
        result.error = "HNVG payload exceeds limit";
        return result;
    }
    const auto total = kGatewayHeaderSize + payload_length;
    if (length < total) return {};

    result.status = DecodeStatus::complete;
    result.consumed = total;
    result.frame.type = data[5];
    result.frame.correlation_id = read_u32_be(data + 8);
    result.frame.payload.assign(data + kGatewayHeaderSize, data + total);
    return result;
}

}  // namespace hypernova
