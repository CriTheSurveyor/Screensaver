# StandBy per Android

La modalità StandBy di iPhone, su Android: quando il telefono è **in carica wireless, in orizzontale e bloccato**, si accende una schermata a tutto schermo.

## Cosa fa
- **3 pagine** (scorri a sinistra/destra): widget, foto, orologio grande.
- **Widget in due stack** (scorri su/giù su ciascuno): orologio analogico, data, batteria | calendario del mese, prossimi eventi, meteo.
- **Orologio a tutto schermo** in 4 stili (scorri su/giù): digitale, analogico, solare, impilato. Tocca per cambiare colore.
- **Foto**: presentazione delle foto che scegli, con ora e data.
- **Modalità notte**: col buio diventa tutto rosso e la luminosità va al minimo.
- Si chiude da sola quando togli il telefono dal caricatore o lo metti in verticale. Indietro per uscire.
- Riparte da sola dopo il riavvio del telefono.

## Come installarla
1. Installa [Android Studio](https://developer.android.com/studio).
2. File → Open → questa cartella (`StandBy`). Aspetta la sincronizzazione di Gradle.
3. Collega il telefono con il debug USB attivo, poi premi ▶ Run.
4. Nell'app: attiva **StandBy automatico** e concedi **Mostra sopra le altre app** (senza questo permesso Android non le lascia aprire la schermata da sola). Consigliato anche **Nessun limite batteria**.

Il meteo usa Open-Meteo (gratuito, senza chiave). I test della logica di rilevamento sono in `app/src/test`.
