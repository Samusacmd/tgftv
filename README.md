# Telegram Fire TV

Client Telegram per Fire TV / Android TV, compilato nel cloud con GitHub Actions.

## Carica e compila
1. Crea un repository **pubblico** su GitHub e carica TUTTA questa cartella
   (con `.github` e `.tdlib-version` inclusi).
2. Su my.telegram.org crea un'app: ottieni **api_id** e **api_hash**.
3. Repo → Settings → Secrets and variables → Actions → New repository secret:
   - `API_ID` = il numero
   - `API_HASH` = la stringa
4. Tab **Actions** → Build APK → Run workflow.
5. A build finita scarica l'artifact `telegram-firetv-apk` (dentro `app-debug.apk`).
6. Installa sul Fire TV (Downloader o `adb install`).

## Uso col telecomando
- Schermata principale: **MENU (☰)** apre le Impostazioni.
- Dentro un canale: **MENU (☰)** alterna griglia / elenco.
- Nel player: **⏭ / ⏮** (tasti media) per file successivo / precedente;
  a fine video passa da solo al successivo. Riprende dal punto in cui eri.

## Impostazioni
- Colonne griglia, larghezza elenco.
- Oscuramento player in pausa (on/off).
- Streaming senza scaricare tutto (SPERIMENTALE, default No).

## Aggiornare TDLib
Modifica `.tdlib-version` (es. `master-2` → `master-3`) per forzare la ricompilazione.

Per build riproducibili conviene **fissare** una versione di TDLib: dopo una build andata
a buon fine, metti in `.tdlib-commit` l'hash del commit di `tdlib/td` che vuoi bloccare
(al posto di `master`). Da quel momento la CI userà sempre quel commit; per aggiornare,
cambia l'hash (o rimetti `master` per tornare all'ultimo disponibile).
