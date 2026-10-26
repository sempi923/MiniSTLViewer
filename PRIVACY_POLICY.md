# Política de Privacidad de Rabbit3D Viewer

**Última actualización:** 26 de octubre de 2026

Rabbit3D Viewer ("la Aplicación", "nosotros") es un visor y analizador técnico de modelos 3D (.STL y .3MF) para dispositivos móviles Android. Esta Política de Privacidad describe nuestras prácticas con respecto a la recopilación, uso y divulgación de información personal cuando utiliza nuestra Aplicación.

---

## 1. Resumen de Privacidad: Cero Recopilación de Datos

> **En pocas palabras:** Rabbit3D Viewer no recopila, no transmite, no almacena en servidores externos ni comparte ningún dato personal ni información del dispositivo. La aplicación funciona de manera 100% local y autónoma en su dispositivo (offline).

---

## 2. Permisos y Acceso a Archivos Locales

* **Storage Access Framework (SAF):** La Aplicación no solicita permisos sensibles de acceso amplio al almacenamiento (`READ_EXTERNAL_STORAGE` o `MANAGE_EXTERNAL_STORAGE`). El acceso a los archivos 3D (.stl y .3mf) se realiza exclusivamente cuando el usuario los selecciona voluntariamente mediante el selector de documentos nativo del sistema operativo Android (`OpenDocument`).
* **Tratamiento de Archivos:** Los archivos seleccionados se analizan, procesan y renderizan exclusivamente en la memoria local (RAM) y GPU del dispositivo mediante OpenGL ES 2.0. Ningún archivo ni metadato geométrico sale del dispositivo.
* **Archivos Temporales:** Cualquier archivo temporal generado durante la descompresión en memoria de modelos .3MF se almacena en el directorio de caché privado de la aplicación (`context.cacheDir`) y se elimina inmediatamente al finalizar el procesamiento.

---

## 3. Conexiones de Red y Telemetría

* **Sin Permiso de Internet:** Rabbit3D Viewer **no** declara ni utiliza el permiso `android.permission.INTERNET`.
* **Sin Telemetría ni Analíticas:** No integramos SDKs de analítica (como Firebase Analytics, Google Analytics o Mixpanel), servicios de seguimiento, identificadores de publicidad ni herramientas de rastreo.
* **Sin Anuncios:** La Aplicación no contiene anuncios ni integra redes publicitarias (AdMob u otras).

---

## 4. Clasificación de Edad y Privacidad de Menores (COPPA / GDPR-K)

Nuestra Aplicación está dirigida al público en general (incluyendo diseñadores, ingenieros y aficionados a la impresión 3D). Debido a que no recopilamos ningún tipo de información personal identificable, Rabbit3D Viewer cumple plenamente con la Ley de Protección de la Privacidad Infantil en Internet (COPPA) y las directrices de privacidad de la Unión Europea (RGPD/GDPR).

---

## 5. Cumplimiento del Formulario de Seguridad de los Datos de Google Play (Data Safety)

De acuerdo con los requisitos del formulario *Data Safety* de Google Play Console:

| Campo de Declaración | Valor Declarado |
| :--- | :--- |
| **¿La aplicación recopila datos?** | **No** (No data collected) |
| **¿La aplicación comparte datos con terceros?** | **No** (No data shared) |
| **¿Los datos se transfieren mediante conexión cifrada?** | No aplica (no hay transferencia de red) |
| **¿El usuario puede solicitar la eliminación de datos?** | No aplica (no se almacenan datos en servidores) |

---

## 6. Seguridad de la Información

Al no transmitir datos hacia o desde servidores externos, no existe riesgo de interceptación de red (*man-in-the-middle*). Todos los cálculos matemáticos, transformaciones y análisis físicos de volumen, masa y voladizos ocurren en los hilos locales del procesador del dispositivo.

---

## 7. Cambios en esta Política de Privacidad

Podemos actualizar nuestra Política de Privacidad periódicamente para reflejar mejoras técnicas o requisitos normativos. Cualquier cambio se publicará en este mismo documento y en la ficha de Google Play Store.

---

## 8. Contacto

Si tiene alguna pregunta, duda o sugerencia con respecto a esta Política de Privacidad, puede contactarnos a través del repositorio oficial del proyecto o por correo electrónico:

* **Desarrollador:** Rabbit3D Engineering Team
* **Repositorio:** [https://github.com/rabbit3d/viewer](https://github.com/rabbit3d/viewer)
* **Correo de contacto:** support@rabbit3d.app
