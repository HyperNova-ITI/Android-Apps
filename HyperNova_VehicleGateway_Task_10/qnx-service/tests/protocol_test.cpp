#include "hypernova/protocol.hpp"

#include <cassert>
#include <cstdint>
#include <iostream>
#include <vector>

int main() {
    const std::vector<std::uint8_t> expected{
        0x01, 0x07, 0x04, 0x16, 0x03, 0x00, 0x01, 0x12, 0xce
    };
    const auto tc = hypernova::encode_tc(0x01, 0x07, {0x16, 0x03, 0x00, 0x01});
    assert(tc == expected);
    const auto decoded_tc = hypernova::decode_tc(tc.data(), tc.size());
    assert(decoded_tc.status == hypernova::DecodeStatus::complete);
    assert(decoded_tc.frame.crc_ok);
    assert(decoded_tc.frame.sequence == 7);
    assert(decoded_tc.frame.payload == std::vector<std::uint8_t>({0x16, 0x03, 0x00, 0x01}));

    auto corrupt = tc;
    corrupt.back() ^= 0xffu;
    assert(!hypernova::decode_tc(corrupt.data(), corrupt.size()).frame.crc_ok);

    const auto gateway = hypernova::encode_gateway(0x10, 42, {22, 3, 0, 1});
    assert(hypernova::decode_gateway(gateway.data(), 15).status
           == hypernova::DecodeStatus::incomplete);
    const auto decoded_gateway = hypernova::decode_gateway(gateway.data(), gateway.size());
    assert(decoded_gateway.status == hypernova::DecodeStatus::complete);
    assert(decoded_gateway.frame.type == 0x10);
    assert(decoded_gateway.frame.correlation_id == 42);
    assert(decoded_gateway.frame.payload == std::vector<std::uint8_t>({22, 3, 0, 1}));

    std::cout << "protocol tests passed\n";
    return 0;
}
