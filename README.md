# FiregramTV

Client Telegram nativo per **Fire TV** e **Android TV**, pensato per il telecomando.
Compilato interamente nel cloud con GitHub Actions (TDLib incluso), senza bisogno di un PC potente.

> App realizzata da zero con Claude, con un pizzico di follia ed un mix di odio che non fa mai male.

---

## Funzionalità

### Home (elenco chat)
- Griglia di chat, gruppi, canali e bot, con miniature.
- Schede **Chat / Archiviate / Cerca**.
- Tasto **☰** in alto a sinistra per aprire le Impostazioni (utile sui telecomandi senza tasto MENU fisico; il tasto MENU fisico fa lo stesso).
- Tasti **▲ / ▼** accanto al ☰: saltano rispettivamente alla prima e all'ultima chat dell'elenco.
- **Tenendo premuto OK per 3 secondi** su una chat: chiede conferma per uscirne, con un messaggio diverso per chat private, bot, gruppi e canali. Dopo l'uscita la lista si aggiorna e la cache si pulisce da sola.
- Intestazione con nome app e numero di build corrente.

### Canali e gruppi (elenco media)
- Vista **griglia** o **elenco** (commutabile), colonne e larghezza configurabili.
- **Scroll infinito**: apertura immediata, caricamento progressivo a blocchi mentre scorri (fino a 1300 elementi).
- **Flag "già visto"**: stellina gialla ⭐ sull'angolo dell'anteprima per i video/audio già riprodotti; **pressione prolungata (OK)** su un file per marcarlo/smarcarlo manualmente.
- Azzeramento dell'elenco dei visti dal menu impostazioni.
- Nome file completo di estensione per i documenti non riproducibili.
- **Post con tastiera a pulsanti** (es. menu con elenco stagioni) mostrati come voce a parte "📋 Post con pulsanti": aprendoli si vede foto/testo/pulsanti, tutti funzionanti.
- I pulsanti che puntano a un altro messaggio dello stesso canale (link `t.me/c/...`) portano dritti lì, risolti ufficialmente tramite TDLib (nessun calcolo manuale sugli id); se il punto è un adesivo-segnalibro, si salta **direttamente** all'elenco file da quel punto in poi.

### Visualizzazione dei post
- Foto con didascalia, messaggi di testo con anteprima link arricchita (locandina + trama, tipica per link a schede serie/film), adesivi statici e animati.
- Se il post punta a un file riproducibile, compare un tasto **▶ Riproduci** che apre il player a schermo intero.

### Player
- **Streaming** senza scaricare tutto il file, con buffer configurabile (5s – 2min); supporto MKV incluso (seek con metadati in coda al file).
- **Buffering iniziale ottimizzato**: attende qualche secondo di contenuto prima di partire (indicatore "Caricamento…"), per evitare i blocchi ripetuti sui dispositivi più lenti.
- Ripresa della riproduzione **dal punto esatto in cui eri**, anche tra sessioni diverse, con ritentativi automatici dopo lo standby prima di ripiegare sul download classico.
- Avanzamento automatico al file successivo; tasti media ⏮/⏭.
- Oscuramento schermo in pausa (opzionale).

### Chat (private, gruppi, bot)
- Nuvolette stile Telegram: le tue a destra, le altrui a sinistra, con **avatar circolari** e nome del mittente nei gruppi.
- **Anteprime di foto e altri media** (video, audio, documenti) direttamente in chat, con miniatura e simbolo ▶; cliccandole si apre la riproduzione a schermo intero.
- Sticker statici (WEBP) e animati (TGS/Lottie).
- Anteprime dei link.
- **Rispondi a un messaggio**: seleziona un messaggio (OK, o pressione prolungata se ha già un'azione) → il cursore va sulla casella di scrittura → invia come risposta Telegram vera, con la citazione (e relativa miniatura) visibile.
- **Risposte dei bot in tempo reale**, incluse quelle che modificano il messaggio di un menu a pulsanti.
- Tastiere inline e comandi bot navigabili col telecomando; l'area pulsanti compare solo quando serve.
- **Lettura a ritroso automatica**: arrivando in cima, i messaggi precedenti si caricano da soli, con la vista che resta ancorata al punto in cui stavi leggendo.

### Aggiornamenti integrati (tasto Update)
- Controlla una cartella condivisa pCloud, individua il `FiregramTV-X.Y.Z.apk` più recente e lo confronta con la versione installata (SemVer).
- Avviso con conferma prima di scaricare; percentuale di download in tempo reale.
- Installazione tramite PackageInstaller: a fine installazione **l'app si riapre da sola** nella nuova versione.
- L'APK scaricato viene eliminato automaticamente al riavvio successivo.

### Impostazioni
- Vista chat, immagini chat, filtro media, colonne griglia, larghezza elenco.
- Oscuramento player, streaming on/off, buffer streaming.
- Azzera file già visti, Informazioni, Update.
- **Disconnetti account** con doppia conferma.
- Navigazione circolare: SU dal primo tasto salta all'ultimo e viceversa.

### Pagina Informazioni
- Nome app, versione installata e la filosofia del progetto.

### Sicurezza e affidabilità
- Database TDLib **cifrato** per le nuove installazioni.
- Protezioni contro i crash a freddo subito dopo il login.
- Versione TDLib **fissata** per build riproducibili (`.tdlib-commit`).

---

## Carica e compila
1. Crea un repository **pubblico** su GitHub e carica TUTTA questa cartella (con `.github` e `.tdlib-version` inclusi).
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

Il CI rinomina automaticamente l'APK in `FiregramTV-X.Y.Z.apk`. Senza incremento, il tasto Update dirà "già aggiornato".

## Pubblicare un aggiornamento
1. Incrementa `semVer` in `app/build.gradle.kts`.
2. Lancia la build e scarica l'artifact.
3. Estrai `FiregramTV-X.Y.Z.apk` dallo zip e caricalo così com'è nella cartella condivisa pCloud.
4. Gli utenti ricevono la nuova versione dal tasto **Update** (le versioni vecchie possono restare: viene scelta sempre la più alta).

## Uso col telecomando
- Home: **☰** (o tasto MENU) apre le Impostazioni; **▲/▼** accanto al ☰ saltano a inizio/fine elenco chat; **OK tenuto premuto 3s** su una chat per uscirne.
- Dentro un canale: **MENU** alterna griglia/elenco; **OK tenuto premuto** su un file = visto/non visto.
- Nelle chat: SU fino in cima carica da solo i messaggi precedenti; **OK** (o pressione prolungata) su un messaggio per rispondere.
- Player: **⏮/⏭** per file precedente/successivo; a fine video passa da solo al successivo; riprende dal punto in cui eri.

## Aggiornare TDLib
Modifica `.tdlib-version` (es. `master-2` → `master-3`) per forzare la ricompilazione.

Per build riproducibili conviene **fissare** una versione di TDLib: dopo una build andata a buon fine, metti in `.tdlib-commit` l'hash del commit di `tdlib/td` che vuoi bloccare (al posto di `master`). Da quel momento la CI userà sempre quel commit; per aggiornare, cambia l'hash (o rimetti `master` per tornare all'ultimo disponibile).
