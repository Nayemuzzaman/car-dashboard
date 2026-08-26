package com.csjotlab.cardashboard.debug

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Release build: there is no mock-mode affordance, and nothing that could set the toggle.
 *
 * The debug implementation lives in `src/debug` and is not compiled into this variant at all, so
 * the guarantee is structural rather than a runtime check someone could invert. Its counterpart
 * here exists only so the single call site in `CarDashboardApp` compiles in both variants.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun DebugMockModeToggle(modifier: Modifier = Modifier) = Unit
