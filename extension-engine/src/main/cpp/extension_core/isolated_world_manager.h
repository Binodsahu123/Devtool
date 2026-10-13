#pragma once
#include <string>

namespace swift::extension {

class IsolatedWorldManager {
public:
    std::string WorldNameForExtension(const std::string& extension_id) const;
};

} // namespace swift::extension
