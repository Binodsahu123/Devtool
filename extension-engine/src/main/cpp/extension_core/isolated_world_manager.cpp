#include "isolated_world_manager.h"

namespace swift::extension {
std::string IsolatedWorldManager::WorldNameForExtension(const std::string& extension_id) const {
    return "isolated_world_" + extension_id;
}
} // namespace swift::extension
