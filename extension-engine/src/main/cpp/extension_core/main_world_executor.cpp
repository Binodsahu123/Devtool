#include "main_world_executor.h"
namespace swift::extension {
bool MainWorldExecutor::ValidateJavaScript(const std::string& code) const {
    return !code.empty() && code.find("javascript:") != 0;
}
}
