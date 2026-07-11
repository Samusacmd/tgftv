# FiregramTV

Client Telegram nativo per **Fire TV** e **Android TV**, pensato per il telecomando.
Compilato interamente nel cloud con GitHub Actions (TDLib incluso), senza bisogno di un PC potente.

> App realizzata da zero con Claude, con un pizzico di follia ed un mix di odio che non fa mai male.

---

## Funzionalità

### Home
- Griglia delle chat (chat, gruppi, canali, bot) con miniature, ancorata in alto senza spazi vuoti.
- Schede **Chat / Archiviate / Cerca** e tasto **☰** per aprire il menu (utile sui telecomandi Android TV senza tasto MENU fisico).
- Intestazione con nome app e versione corrente.
- Aggiornamento della lista con debounce: niente sfarfallii all'avvio.

### Canali e liste media
- Vista **griglia** o **elenco** (commutabile), con colonne e larghezza configurabili.
- **Scroll infinito**: apertura immediata con caricamento progressivo a blocchi mentre scorri, fino a 1300 media.
- **Flag "già visto"**: stellina gialla ⭐ sull'angolo dell'anteprima per i video/audio già riprodotti.
- **Pressione prolungata (OK)** su un file per marcarlo/smarcarlo manualmente come già riprodotto.
- Azzeramento dell'intero elenco dei visti dal menu impostazioni.
- Nome file completo di estensione per i documenti non riproducibili.

### Player
- **Streaming senza scaricare tutto il file**, con buffer configurabile (5s – 2min); supporto MKV incluso (seek con metadati in coda al file).
- Ripresa della riproduzione **dal punto esatto in cui eri**, anche tra sessioni diverse.
- Ripresa robusta dopo lo standby: ritentativi automatici dello streaming, poi ripiego sul download classico con gestione errori.
- Avanzamento automatico al file successivo; tasti media ⏮/⏭ per navigare tra i file.
- Oscuramento schermo in pausa (opzionale), pensato per l'ascolto di audio lunghi.

### Chat (private, gruppi, bot)
- Nuvolette stile Telegram: le tue a destra in azzurro, le altrui a sinistra, con nome del mittente nei gruppi.
- **Anteprime delle foto** direttamente in chat (miniatura immediata, poi versione migliorata), cliccabili a schermo intero.
- **Sticker**: statici (WEBP) e animati (TGS/Lottie).
- Anteprime dei link.
- **Risposte dei bot in tempo reale**, incluse quelle che modificano il messaggio del menu (bot a pulsanti).
- Tastiere inline e comandi bot navigabili col telecomando; l'area pulsanti compare solo quando serve.
- **Lettura a ritroso automatica**: arrivando in cima, i messaggi precedenti si caricano da soli e la vista resta ancorata a dove stavi leggendo.
- Invio messaggi con tastiera a schermo (nelle chat dove è abilitata la scrittura).

### Aggiornamenti integrati (tasto Update)
- Controlla una cartella condivisa pCloud, individua il `FiregramTV-X.Y.Z.apk` più recente e lo confronta con la versione installata.
- Avviso con **conferma** prima di scaricare; percentuale di download in tempo reale.
- Installazione tramite PackageInstaller: a fine installazione **l'app si riapre da sola** nella nuova versione.
- L'APK scaricato viene eliminato automaticamente al riavvio successivo.

### DNS per le connessioni dell'app
- Selettore nel menu: **Sistema / Cloudflare / Google / Quad9 / AdGuard**.
- Vale per le funzioni internet dell'app (es. aggiornamenti); utile se l'operatore oscura certi domini a livello DNS.
- Ripiego automatico sul DNS di sistema in caso di problemi: la scelta non può mai bloccare l'app.

### Impostazioni (menu a griglia, tasti compatti)
- Vista chat, immagini chat, filtro media, colonne griglia, larghezza elenco.
- Oscuramento player, streaming on/off, buffer streaming.
- Azzera file già visti, DNS app, Informazioni, Update.
- **Disconnetti account** con doppia conferma (rosso).
- Navigazione circolare: SU dal primo tasto salta all'ultimo e viceversa.

### Pagina Informazioni
- Rick, nome app, versione installata e la filosofia del progetto.

### Sicurezza e affidabilità
- Database TDLib **cifrato** per le nuove installazioni.
- Protezioni contro i crash a freddo subito dopo il login.
- Versione TDLib **fissata** per build riproducibili (`.tdlib-commit`).

---

## Carica e compila
1. Crea un repository **pubblico** su GitHub e carica TUTTA questa cartella
   (con `.github` e `.tdlib-version` inclusi).
2. Su my.telegram.org crea un'app: ottieni **api_id** e **api_hash**.
3. Repo → Settings → Secrets and variables → Actions → New repository secret:
   - `API_ID` = il numero
   - `API_HASH` = la stringa
4. Tab **Actions** → Build APK → Run workflow.
5. A build finita scarica l'artifact `FiregramTV-apk` (dentro c'è `FiregramTV-X.Y.Z.apk`).
6. Installa sul Fire TV / Android TV (Downloader o `adb install`).

## Versionamento (SemVer)
La versione è la costante `val semVer = "X.Y.Z"` in `app/build.gradle.kts`, **da incrementare a mano prima di ogni release**:
- `PATCH` (x.y.**Z**) per correzioni di bug
- `MINOR` (x.**Y**.0) per nuove funzionalità
- `MAJOR` (**X**.0.0) per cambi radicali

Il CI rinomina automaticamente l'APK in `FiregramTV-X.Y.Z.apk`. Attenzione: senza incremento, il tasto Update dirà "già aggiornato".

## Pubblicare un aggiornamento
1. Incrementa `semVer` in `app/build.gradle.kts`.
2. Lancia la build e scarica l'artifact.
3. Estrai `FiregramTV-X.Y.Z.apk` dallo zip e caricalo **così com'è** nella cartella condivisa pCloud.
4. Gli utenti ricevono la nuova versione dal tasto **Update** (le versioni vecchie possono restare nella cartella: viene scelta sempre la più alta).

## Uso col telecomando
- Home: **☰** (o tasto MENU fisico) apre le Impostazioni.
- Dentro un canale: **MENU** alterna griglia / elenco; **OK tenuto premuto** su un file = visto/non visto.
- Nelle chat: SU fino in cima carica da solo i messaggi precedenti.
- Player: **⏮ / ⏭** per file precedente / successivo; a fine video passa da solo al successivo; riprende dal punto in cui eri.

