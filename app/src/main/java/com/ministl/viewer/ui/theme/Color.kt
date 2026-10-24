package com.ministl.viewer.ui.theme

import androidx.compose.ui.graphics.Color

// Paleta Técnica Industrial (Inspirada en Bambu Studio / Bambu Handy)
val BambuGraphiteBg = Color(0xFF121316)       // Fondo grafito oscuro principal
val BambuSlateSurface = Color(0xFF1E2025)     // Paneles y tarjetas primarias
val BambuSlateElevated = Color(0xFF2A2D35)    // Controles, pestañas e inputs elevados
val BambuBorderSubtle = Color(0xFF363A45)     // Bordes técnicos de 1dp
val BambuGlassSurface = Color(0xD91E2025)     // Superficie flotante translúcida (85% alpha)

// Acentos Industriales Bambu Lab
val BambuGreenPrimary = Color(0xFF00AE42)     // Verde Bambu principal
val BambuGreenBright = Color(0xFF00BE4E)      // Verde Bambu brillante para estados activos/hover
val BambuGreenDim = Color(0xFF004D1D)         // Fondo verde atenuado para badges y selección

// Textos y Jerarquía Visual
val BambuTextPrimary = Color(0xFFF2F4F8)      // Blanco técnico principal
val BambuTextSecondary = Color(0xFF9EA4B0)    // Gris acero para etiquetas
val BambuTextMuted = Color(0xFF686E7A)        // Gris apagado para unidades y subtítulos

// Semánticos y Ejes 3D
val OverhangAlertRed = Color(0xFFFF3B30)      // Rojo alerta para voladizos (> 45°)
val WarningAmber = Color(0xFFF59E0B)          // Ámbar para avisos técnicos
val AxisXRed = Color(0xFFFF4D4F)              // Eje X (Rojo CAD)
val AxisYGreen = Color(0xFF00BE4E)            // Eje Y (Verde CAD)
val AxisZBlue = Color(0xFF3B82F6)             // Eje Z (Azul CAD)

// Catálogo de Colores de Filamento Bambu Lab
data class FilamentSwatches(val name: String, val hex: String, val color: Color)

val BambuFilamentPalette = listOf(
    FilamentSwatches("Bambu Green", "#00AE42", Color(0xFF00AE42)),
    FilamentSwatches("Jade White", "#F4F5F0", Color(0xFFF4F5F0)),
    FilamentSwatches("Charcoal Black", "#26282A", Color(0xFF26282A)),
    FilamentSwatches("Space Gray", "#7A828C", Color(0xFF7A828C)),
    FilamentSwatches("Mandarin Orange", "#FF6A13", Color(0xFFFF6A13)),
    FilamentSwatches("Scarlet Red", "#D9232E", Color(0xFFD9232E)),
    FilamentSwatches("Cobalt Blue", "#1D63DC", Color(0xFF1D63DC)),
    FilamentSwatches("Sunflower Yellow", "#F7C600", Color(0xFFF7C600)),
    FilamentSwatches("Silk Gold", "#E5B84B", Color(0xFFE5B84B)),
    FilamentSwatches("Silk Silver", "#B8C0C8", Color(0xFFB8C0C8)),
    FilamentSwatches("Silk Copper", "#C86D51", Color(0xFFC86D51)),
    FilamentSwatches("Cyan Tough", "#00A3C4", Color(0xFF00A3C4))
)
