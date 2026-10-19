package com.example.tiny

import com.missing.dep.Widget

/** Renders a widget through a type this library cannot resolve. */
public fun render(widget: Widget): Widget = widget

@Deprecated("Widgets are going away", ReplaceWith("render(widget)"))
public fun renderLegacy(widget: Widget): Widget = render(widget)
