package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.catalog.api.CatalogSpuView;

record ResolvedProductCandidate(CatalogSpuView spu, Double score, String reason) {
}
