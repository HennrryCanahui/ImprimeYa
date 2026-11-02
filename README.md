# ImprimeYa

Aplicación nativa para Android orientada a la impresión directa de recibos y facturas en impresoras térmicas conectadas por Wi-Fi. Diseñada como una alternativa ligera, sin anuncios y de código abierto para reemplazar soluciones de terceros.

## Características principales

* **Integración con el sistema**: Implementación de `PrintService` de Android para interceptar solicitudes de impresión desde cualquier aplicación (incluyendo navegadores y sistemas FEL).
* **Comunicación directa por red**: Conexión por sockets TCP/IP al puerto 9100 de la impresora.
* **Sin middleware**: Procesamiento local rápido sin depender de servicios externos ni publicidad.

## Stack técnico

* **Lenguaje**: Kotlin
* **UI**: Jetpack Compose
* **API de impresión**: `android.printservice`

## Estado

En desarrollo inicial.
