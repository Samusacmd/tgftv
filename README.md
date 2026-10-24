# FiregramTV

Client Telegram nativo per **Fire TV** e **Android TV**, pensato per il telecomando.
Compilato interamente nel cloud con GitHub Actions (TDLib incluso), senza bisogno di un PC potente.

> App realizzata da zero con Claude, con un pizzico di follia ed un mix di odio che non fa mai male.

---

## Funzionalità

### Home (elenco chat)
- Griglia o elenco di chat, gruppi, canali e bot, con miniature.
- Schede **Chat / Archiviate / Cerca**.
- **Cerca**: tra "Le mie chat" e i "Canali pubblici" di Telegram.
- Tasto **☰** in alto a sinistra per aprire le Impostazioni (il tasto MENU fisico fa lo stesso).
- Tasti **▲ / ▼**: saltano alla prima e all'ultima chat dell'elenco.
- Tasto **↻**: aggiorna l'elenco che stai guardando (Chat o Archiviate).
- **Tenendo premuto OK per 3 secondi** su una chat: chiede conferma per uscirne, con un messaggio diverso per chat private, bot, gruppi e canali. Dopo l'uscita l'elenco si aggiorna da solo.
- Intestazione con nome app e numero di build corrente.

### Canali e gruppi (elenco file)
- Vista **griglia** o **elenco** (commutabile), con colonne e larghezza configurabili.
- **Scroll infinito**: apertura immediata, nuovi file caricati mentre scorri.
- Video, audio, foto e **PDF** nello stesso elenco; il filtro "Mostra" nelle Impostazioni permette di vedere tutto oppure solo video e audio.
- **Stellina "già visto"** ⭐ sull'anteprima dei file già aperti; **OK tenuto premuto** su un file per segnarlo o togliere il segno a mano.
- **Post con pulsanti** (es. menu con l'elenco delle stagioni) mostrati come voce a parte "📋 Post con pulsanti": si vedono immagine, testo e pulsanti, tutti utilizzabili.
- I pulsanti che portano a un altro punto dello stesso canale ti ci portano direttamente; se il punto è un adesivo-segnalibro, si apre subito l'elenco dei file da lì in poi.

### Visualizzazione dei post
- Foto con didascalia, testi con anteprima del link (locandina e trama), adesivi statici e animati.
- Se il post contiene un file riproducibile, compare il tasto **▶ Riproduci**.

### Player (video e audio)
- **Streaming** senza scaricare tutto il file, con buffer regolabile; supporta anche i file MKV.
- **Caricamento iniziale** di qualche secondo (scritta "Caricamento…") per evitare i blocchi sui dispositivi più lenti.
- **Ripresa dal punto esatto** in cui eri, anche a distanza di giorni; dopo lo standby riprova da solo.
- **Ordine cronologico**: ⏭ e il passaggio automatico a fine file portano al file pubblicato **dopo**, ⏮ a quello pubblicato prima.
- Oscuramento dello schermo in pausa (opzionale).

### Lettore PDF
- I PDF si aprono direttamente nell'app, sia dall'elenco file sia dalle chat.
- Si legge una pagina alla volta, adattata allo schermo. L'app tiene pronte solo la pagina corrente e le due vicine, per restare leggera anche con PDF molto grandi.
- **Zoom** a 1x, 2x e 3x con **OK**; con lo zoom attivo le frecce spostano la vista, e oltre il bordo si passa alla pagina successiva o precedente.
- Indicatore del numero di pagina e **ripresa dall'ultima pagina letta**.
- Quando esci dal PDF, il file non viene cancellato subito: se torni nella chat e lo riapri, è già pronto. Viene eliminato dal dispositivo quando arrivi alla home.
- I PDF protetti da password non sono supportati.

### Chat (private, gruppi, bot)
- Nuvolette in stile Telegram: le tue a destra, le altrui a sinistra, con **avatar** e nome del mittente nei gruppi.
- **Anteprime** di foto, video, audio e documenti direttamente in chat; con OK si aprono a schermo intero (i PDF nel lettore PDF).
- Sticker statici e animati, anteprime dei link.
- **Rispondi a un messaggio**: selezionalo, scrivi e invia come vera risposta Telegram, con la citazione visibile.
- **Risposte dei bot in tempo reale**, anche quando modificano un menu a pulsanti.
- Tastiere dei bot e comandi (es. `/start`) utilizzabili col telecomando.
- **Lettura a ritroso automatica**: arrivando in cima, i messaggi più vecchi si caricano da soli senza perdere il punto in cui stavi leggendo.

### Aggiornamenti integrati (tasto Update)
- Controlla se esiste una versione più recente e la confronta con quella installata.
- Chiede conferma prima di scaricare e mostra la percentuale.
- Al termine dell'installazione **l'app si riapre da sola** nella nuova versione.
- Il file di aggiornamento scaricato viene eliminato automaticamente.

### Impostazioni
- Vista chat (griglia o elenco), immagini nelle chat, filtro media, colonne della griglia, larghezza dell'elenco.
- Oscuramento del player, streaming on/off e buffer dello streaming.
- **Abilita scrittura**: scegli in quali tipi di chat poter scrivere (private, bot, gruppi, gruppi con sottotopic, canali dove sei admin).
- **Svuota cache**: elimina file temporanei, aggiornamenti scaricati e tutti i media scaricati, anche a metà, e mostra quanti MB ha liberato. Le stelline e le posizioni di ripresa restano.
- **Azzera file già visti**.
- Informazioni, Update, **Disconnetti account** con doppia conferma.
- Navigazione circolare: SU dal primo tasto va all'ultimo e viceversa.

### Pagina Informazioni
- Nome dell'app, versione installata e filosofia del progetto.

### Sicurezza
- Database dei dati di Telegram **cifrato** sul dispositivo.

---

## Carica e compila
L'app usa due repository: uno per l'app e uno privato che prepara TDLib.

1. **Repository app**: carica tutta la cartella del progetto (compresa `.github`).
2. Su my.telegram.org crea un'app per ottenere **api_id** e **api_hash**.
3. Nel repository dell'app: Settings → Secrets and variables → Actions → New repository secret, e crea:
   - `API_ID` = il numero
   - `API_HASH` = la stringa
   - `TDLIB_REPO_TOKEN` = il token di accesso al repository TDLib
4. **Repository TDLib** (`tdlib-android-build`): tab Actions → "Build & Publish TDLib (Android)" → Run workflow. Serve solo la prima volta, o quando vuoi aggiornare TDLib (la compilazione richiede alcune ore).
5. **Repository app**: tab Actions → Build APK → Run workflow.
6. A build finita scarica l'artifact `FiregramTV-apk` (dentro c'è `FiregramTV-X.Y.Z.apk`) e installalo sul Fire TV / Android TV (Downloader o `adb install`).

## Versionamento (SemVer)
La versione è la riga `val semVer = "X.Y.Z"` in `app/build.gradle.kts`, **da incrementare a mano prima di ogni release**:
- `PATCH` (x.y.**Z**) per correzioni di bug
- `MINOR` (x.**Y**.0) per nuove funzionalità
- `MAJOR` (**X**.0.0) per cambi radicali

L'APK prodotto si chiama automaticamente `FiregramTV-X.Y.Z.apk`. Senza incremento, il tasto Update dirà "già aggiornato".

## Pubblicare un aggiornamento
1. Incrementa `semVer` in `app/build.gradle.kts`.
2. Lancia la build e scarica l'artifact.
3. Estrai `FiregramTV-X.Y.Z.apk` dallo zip e caricalo così com'è nella cartella condivisa pCloud.
4. Gli utenti ricevono la nuova versione dal tasto **Update** (le versioni vecchie possono restare: viene scelta sempre la più alta).

## Uso col telecomando
- **Home**: ☰ (o MENU) apre le Impostazioni; ▲/▼ saltano a inizio e fine elenco; ↻ aggiorna; **OK tenuto premuto 3 s** su una chat per uscirne.
- **Dentro un canale**: MENU alterna griglia ed elenco; **OK tenuto premuto** su un file = visto / non visto.
- **Nelle chat**: SU fino in cima carica i messaggi precedenti; **OK** (o pressione prolungata) su un messaggio per rispondere.
- **Player**: ⏮/⏭ per file precedente e successivo; a fine file passa da solo al successivo.
- **PDF**: frecce per cambiare pagina, **OK** per lo zoom (1x → 2x → 3x → 1x).

## Aggiornare TDLib
Nel repository `tdlib-android-build` rilancia il workflow "Build & Publish TDLib (Android)": pubblica una nuova versione, che la build dell'app scarica automaticamente. Se lasci vuoto il campo commit, usa l'ultima versione disponibile.
