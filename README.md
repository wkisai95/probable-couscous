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
