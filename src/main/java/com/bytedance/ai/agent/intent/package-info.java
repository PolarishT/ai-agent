/**
 * Agent intent classification internals.
 *
 * <p>The primary path is LLM-based classification. Local rules remain only as an offline fallback
 * when the chat model is unavailable or returns invalid structured output.
 */
package com.bytedance.ai.agent.intent;
