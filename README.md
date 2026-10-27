# probable-couscous · tokenarb

Scanner **on-chain su Base** che confronta i **tokenized stocks emessi da Coinbase** (standard B20: AAPLc, NVDAc, TSLAc…) con il prezzo delle stesse azioni a **Wall Street**. Gira ogni **15 minuti**, calcola **size ottimale e profitto netto** dell'arbitraggio e invia **notifiche** (Telegram, Discord, ntfy).

## Come funziona

1. **Solo token Coinbase, tutti.** A ogni scansione la lista completa viene scaricata dall'API ufficiale `api.coinbase.com/v1/tokenized-stocks`, leggendo tutte le pagine. Ogni token arriva con indirizzo, decimali e `multiplier`.
   - I nuovi listing entrano da soli, senza toccare il codice.
   - I token sono identificati per indirizzo, quindi copie con lo stesso ticker ed emittenti diversi da Coinbase vengono ignorati.
   - I token senza liquidità on-chain vengono saltati.
   - `python -m tokenarb tokens` mostra l'elenco aggiornato.
2. **Prezzo on-chain eseguibile.** Le quote arrivano dall'aggregatore KyberSwap su Base, che instrada su Aerodrome, Uniswap e gli altri pool. Il prezzo include già fee dei pool, slippage reale per la size e costo del gas.
3. **Prezzo equo per token:**

   ```
   prezzo Borsa (Yahoo/Finnhub) × multiplier
   ```

   Il multiplier assorbe dividendi e split (1 token ≠ sempre 1 azione). Come controllo incrociato si leggono i feed Chainlink *Base Tokenized Equities*, che fanno anche da riserva se Yahoo non risponde.
4. **Direzione:**
   - token sotto il prezzo di Borsa → **compra il token con USDC su Base + short dell'azione**;
   - token sopra → **vendi il token per USDC + compra l'azione**.

   Per impostazione predefinita sono considerati **solo gli arbitraggi in cui conviene comprare il token** (`only_buy_token: true` in `config.yaml`, interruttore nelle impostazioni dell'app).
5. **Size del profitto.** Su un AMM il prezzo medio peggiora con la size: il profitto netto sale, tocca un massimo e poi scende. Lo scanner chiede quote reali su una scala di size (250 $ → 50.000 $), si ferma quando il profitto cala e raffina attorno al massimo.

   ```
   netto = valore copertura in Borsa − USDC spesi (o il contrario)
           − gas − commissioni broker − metà spread azione − costi fissi
   ```

   La notifica riporta la size ottimale, il netto, la scala *size → netto* e il risultato con una size fissa di confronto.
6. **Filtri.** Notifica solo se:
   - la Borsa è aperta (si può coprire);
   - il prezzo di riferimento è fresco;
   - il contratto non ha funzioni in pausa;
   - il netto supera le soglie.

   Lo stesso alert non si ripete per 60 minuti, salvo che il gap si allarghi.

## Avvio rapido

```bash
pip install -r requirements.txt
python -m tokenarb scan --no-notify   # una scansione, tabella a console
python -m tokenarb run                 # loop ogni 15 minuti (:00 :15 :30 :45)
python -m tokenarb test-notify         # prova le notifiche
```

Lo storico di ogni scansione finisce in `data/scans.csv`.

## Notifiche

| Canale   | Variabili d'ambiente / secret GitHub |
|----------|--------------------------------------|
| Telegram | `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` |
| Discord  | `DISCORD_WEBHOOK_URL` |
| ntfy     | `NTFY_TOPIC` (opz. `NTFY_SERVER`) |

## GitHub Actions

`.github/workflows/scanner.yml` lancia una scansione ogni 15 minuti; si può avviare anche a mano da *Actions → Run workflow*. Le notifiche si attivano aggiungendo i secret in *Settings → Secrets and variables → Actions*. Il cron di GitHub può ritardare di qualche minuto.

## Configurazione (`config.yaml`)

- **Ticker:** vuoto = tutti i token Coinbase.
- **Rete e asset:** RPC di Base, indirizzo USDC, feed Chainlink.
- **Costi:** commissioni del broker per la copertura.
- **Sizing:** scala delle size, capitale massimo, soglie di notifica.
- **Sessioni operabili.**

## Avvertenze

- I token si scambiano 24/7, la Borsa no. Fuori orario il gap è registrato ma **non notificato**: non c'è copertura possibile e il feed Chainlink è fermo.
- *Compra token / short azione* richiede di poter andare short. *Vendi token* richiede inventario di token.
- Questo scanner rileva e misura le opportunità, **non esegue operazioni**.
- I token Coinbase sono offerti solo in giurisdizioni idonee fuori dagli USA (Reg. S). Verifica di poterli detenere.
- Non è consulenza finanziaria.

## App Android

La cartella `android/` contiene un'app nativa che fa le stesse scansioni direttamente dal telefono:
- scansiona ogni 15 minuti in background, con notifiche locali e senza server;
- **prezzi on-chain a ogni scansione da DexScreener e poi GeckoTerminal**: DexScreener (pool token/USDC su Aero, ~0,2 s per tutti i token) manda subito a schermo gli arbitraggi; GeckoTerminal parte subito dopo, aggiunge i token che DexScreener non indicizza e sostituisce il prezzo quando trova una pool molto più liquida;
- gli arbitraggi compaiono **subito** dopo il confronto con Wall Street; prezzo eseguibile, size ottimale e profitto netto vengono poi verificati on-chain con KyberSwap;
- di base mostra solo i token che conviene comprare (sotto il prezzo di Borsa, con profitto netto positivo); dalle impostazioni si può tornare alla vista completa;
- tocca una riga per i dettagli, tieni premuto per aprire la pool su DexScreener;
- si regola dalle impostazioni nell'app: capitale, soglie, commissioni del broker, copertura in pre/after-market.

**Posizioni e uscita**
- Il pulsante **Ho comprato** registra l'acquisto (importo in USD e prezzo medio).
- A ogni scansione l'app cerca la quota più grande della posizione (a passi del 10%) vendibile on-chain a un prezzo medio che chiude il gap con la Borsa (entro la tolleranza impostata, 10 bps di default).
- Notifica quando si può vendere almeno il 10% (impostabile); nuovo avviso se la quota vendibile cresce, promemoria al più ogni ora.
- **Ho venduto** riduce o chiude la posizione; **Elimina** smette di monitorarla.

**Installazione**
1. Scarica `TokenArb.apk` dall'ultima [Release](../../releases/latest) direttamente dal telefono.
2. Aprilo e consenti l'installazione da questa fonte quando Android lo chiede.
3. Al primo avvio consenti le notifiche e tocca **Consenti scansioni in background**: senza l'esenzione dal risparmio batteria Android può ritardare le scansioni.

L'APK viene compilato da GitHub Actions (`.github/workflows/android.yml`) a ogni modifica e pubblicato nelle Release. Gli aggiornamenti si installano sopra la versione precedente.

## Copertura dei dati (verificata il 27/10/2026)

- **218 token** emessi da Coinbase su Base, **81** con almeno un pool DEX.
- Feed prezzi: DexScreener risponde in **0,2 s** per tutti i token contro **24 s** di GeckoTerminal, con prezzi coerenti entro pochi decimi di punto; non indicizza però alcune pool Aero (es. TSLAc, MSTRc, MUc, la pool principale di AMZNc), che arrivano da GeckoTerminal nella stessa scansione. Confronto aggiornato: `python -m tokenarb feeds`.
- La liquidità reale sta quasi tutta su **Aero** (`aero-slipstream-base`), poi Uniswap v4 e Aerodrome.
- KyberSwap instrada tutti i token con liquidità reale; i 19 pool che non instrada hanno liquidità zero.
- Per l'elenco aggiornato con i pool di ogni token: `python -m tokenarb tokens --liquidity`, oppure *Actions → scanner → Run workflow → tokens*.
