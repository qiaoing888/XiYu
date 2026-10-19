#ifndef XY_OTP_H
#define XY_OTP_H

#include <string>
#include <optional>

namespace xy {

std::optional<std::string> parseOtp(const std::string &text);

} // namespace xy

#endif // XY_OTP_H
