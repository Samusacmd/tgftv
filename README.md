# Telegram Fire TV

Client Telegram per Fire TV / Android TV, compilato **interamente nel cloud**
tramite GitHub Actions. Il tuo PC non deve installare nulla: serve solo il browser.

Funzioni di questa prima versione:
- Login al tuo account Telegram (numero → codice → password 2FA)
- Lista delle tue chat navigabile col telecomando (interfaccia Leanback TV)
- Player ExoPlayer cablato (la riproduzione del media reale è il prossimo passo)

---

## Cosa fa la GitHub Action

1. Compila **TDLib** (la libreria ufficiale Telegram in C++) per Android via Docker
2. La mette in cache, così dalla seconda volta in poi è velocissima
3. Inserisce librerie native e sorgenti nel progetto
4. Costruisce l'`app-debug.apk`
5. Te lo mette a disposizione come "artifact" da scaricare

---

## Procedura passo passo

### 1. Credenziali Telegram
Vai su **https://my.telegram.org** → *API development tools* → crea un'app.
Annota **api_id** (numero) e **api_hash** (stringa).

### 2. Crea il repository (PUBBLICO)
Su GitHub: *New repository*. Scegli **Public**: i minuti di Actions sono gratis
e illimitati. (Le tue credenziali NON finiscono nel codice — vanno nei Secrets, sicuri.)

### 3. Carica il progetto
Trascina tutta questa cartella nella pagina del repo (*Add file → Upload files*),
oppure via git:
```
git init
git add .
git commit -m "primo commit"
git branch -M main
git remote add origin https://github.com/TUO-UTENTE/TUO-REPO.git
git push -u origin main
```

### 4. Inserisci i Secrets
Nel repo: *Settings → Secrets and variables → Actions → New repository secret*.
Crea due secret:
- `API_ID`   → il tuo api_id
- `API_HASH` → il tuo api_hash

### 5. Avvia la build
Tab **Actions** → workflow *Build APK* → **Run workflow**.
(Si avvia anche da solo a ogni push.)

### 6. Aspetta
**Prima volta: 30–60 minuti** (compila TDLib da zero).
Volte successive: **pochi minuti** grazie alla cache.

### 7. Scarica l'APK
A build finita (spunta verde), apri il run → sezione **Artifacts** →
scarica `telegram-firetv-apk`. Dentro c'è `app-debug.apk`.

### 8. Installa sul Fire TV
- *Impostazioni → Il mio Fire TV → Opzioni sviluppatore → App da fonti sconosciute: ON*
- Usa l'app **Downloader** per scaricare/installare l'APK, oppure via **ADB**:
  `adb connect IP_DEL_FIRETV` poi `adb install app-debug.apk`

---

## Aggiornare TDLib
Per forzare una nuova compilazione di TDLib, modifica una qualsiasi cosa nel file
`.tdlib-version` (es. da `master-1` a `master-2`): la cache si invalida e ricompila.

## Se la build fallisce
È normale al primo giro su un progetto nuovo. Apri il run fallito, copia il log
dello step in rosso e mandamelo: gli errori della CI sono chiari e riproducibili,
li sistemiamo insieme uno alla volta.
