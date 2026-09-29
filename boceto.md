# Boceto: descargador remoto de videos de X

## Idea

Desde la app de X en el teléfono, usar el menú **Compartir** para mandar el link del tweet a una computadora local, que descarga el video automáticamente con `yt-dlp`.

> Nota: el botón nativo "Descargar" de la app de X guarda el video en el teléfono y no se puede interceptar. Por eso se usa el menú de compartir como punto de entrada.

## Flujo

```
[App de X]
   │  Compartir → acción personalizada
   ▼
[Teléfono: HTTP Shortcuts (Android) / Atajos (iPhone)]
   │  POST {"url": "..."} + token
   ▼
[Tailscale: red privada teléfono ↔ PC]
   │
   ▼
[PC: servidor FastAPI]
   │  valida token y URL
   ▼
[yt-dlp]
   │
   ▼
[Carpeta ~/Descargas]
```

## Componentes

### Teléfono
- **Android:** HTTP Shortcuts (aparece en el menú de compartir y hace el POST) o Tasker.
- **iPhone:** app Atajos con la acción "Obtener contenido de URL", recibiendo la entrada desde el menú de compartir.

### Conexión
- **Tailscale** instalado en el teléfono y en la PC.
- Ambos quedan en la misma red privada, sin abrir puertos al internet.

### PC (servidor)
- FastAPI (o Flask) escuchando en el puerto 8000.
- `yt-dlp` para descargar (soporta videos de X).

## Esqueleto del servidor

```python
from fastapi import FastAPI, Header, HTTPException
import subprocess

app = FastAPI()
TOKEN = "cambia-esto"

@app.post("/descargar")
def descargar(data: dict, authorization: str = Header(None)):
    if authorization != f"Bearer {TOKEN}":
        raise HTTPException(401)
    subprocess.Popen(["yt-dlp", "-o", "~/Descargas/%(title)s.%(ext)s", data["url"]])
    return {"ok": True}
```

Ejecutar:

```bash
uvicorn servidor:app --host 0.0.0.0 --port 8000
```

Desde el teléfono: `POST http://IP-de-tailscale:8000/descargar` con el cuerpo `{"url": "..."}` y el header `Authorization: Bearer <TOKEN>`.

## Seguridad

- Usar siempre un token y no exponer el puerto directamente a internet.
- Validar que la URL sea de `x.com` o `twitter.com` antes de pasarla a `yt-dlp`.
- Preferir pasar los argumentos como lista (no como string de shell) para evitar inyección de comandos.

## Limitaciones

- La PC debe estar encendida. Para disponibilidad continua, se puede correr en una Raspberry Pi o un VPS.
- Depende de que `yt-dlp` siga soportando X (se rompe de vez en cuando; actualizarlo con `yt-dlp -U`).

## Pendiente por definir

- [ ] Sistema del teléfono (Android o iPhone)
- [ ] Sistema operativo de la PC
- [ ] Dónde se guardan los videos y si se quiere notificación al terminar
- [ ] Ejecutar el servidor como servicio (arranque automático)
