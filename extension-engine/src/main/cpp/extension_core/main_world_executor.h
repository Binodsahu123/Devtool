#pragma once
#include <string>
namespace swift::extension {
class MainWorldExecutor {
public:
    bool ValidateJavaScript(const std::string& code) const;
};
}
