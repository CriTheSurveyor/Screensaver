# StandBy per Android

La modalità StandBy di iPhone, su Android: quando il telefono è **in carica wireless, in orizzontale e bloccato**, si accende una schermata a tutto schermo.

## Cosa fa
- **3 pagine** (scorri a sinistra/destra): widget, foto, orologio grande.
- **Widget**: a sinistra la ricarica nel dettaglio (percentuale con i centesimi, watt, mA, volt, temperatura, tempo alla carica completa); a destra ora, data, meteo, notifiche, prossimo evento e sveglia.
- **Orologio a tutto schermo** in 4 stili (scorri su/giù): digitale, analogico, solare, impilato. Tocca per cambiare colore.
- **Foto**: presentazione delle foto che scegli, con ora e data.
- **Notifiche**: ogni nuova notifica appare grande al centro (icona dell'app, come su iPhone), dopo 4 secondi rimpicciolisce e finisce nel riquadro Notifiche dei widget; toccala per leggere tutto il testo. Mentre StandBy è aperto il banner di sistema è bloccato (Non disturbare con sveglie, chiamate e media consentiti; le impostazioni tornano come prima all'uscita). Serve **Notifiche nello StandBy** nelle impostazioni dell'app.
- **Meteo animato**: dietro il riquadro meteo il cielo si muove secondo il tempo attuale (sole, stelle, nuvole, pioggia, neve, nebbia, temporale).
- **Ora personalizzabile**: carattere (7 a scelta) e spessore dell'ora separati dal resto del testo.
- **Aggiornamenti**: ogni push su GitHub crea una Release; si installa dal tasto Versione nell'app.
- **Modalità notte**: col buio diventa tutto rosso e la luminosità va al minimo.
- Prende il posto dello schermo spento: ogni volta che lo schermo si spegne sul supporto, torna StandBy.
- **Tasto di accensione**: dalla schermata StandBy porta al telefono (sblocco e home).
- Si chiude da sola quando togli il telefono dal caricatore o lo metti in verticale.
- Riparte da sola dopo il riavvio del telefono.

## Come installarla
1. Installa [Android Studio](https://developer.android.com/studio).
2. File → Open → questa cartella (`StandBy`). Aspetta la sincronizzazione di Gradle.
3. Collega il telefono con il debug USB attivo, poi premi ▶ Run.
4. Nell'app: attiva **StandBy automatico** e concedi **Mostra sopra le altre app** (senza questo permesso Android non le lascia aprire la schermata da sola). Consigliato anche **Nessun limite batteria**.

Il meteo usa Open-Meteo (gratuito, senza chiave). I test della logica di rilevamento sono in `app/src/test`.
