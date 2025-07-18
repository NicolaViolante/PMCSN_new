package org.uniroma2.PMCSN.centers;

import org.uniroma2.PMCSN.configuration.ConfigurationManager;
import org.uniroma2.PMCSN.controller.Sistema;
import org.uniroma2.PMCSN.libs.Rngs;
import org.uniroma2.PMCSN.model.*;
import org.uniroma2.PMCSN.utils.Distrs;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class RideSharingMultiServerNode implements Node {

    private static final int ARRIVAL = 0;
    private final Rngs rng;


    private final MsqTime clock = new MsqTime();
    private final Area areaCollector = new Area();
    private final Distrs distrs = new Distrs();
    private final MsqSum[] sum;
    private final List<MsqEvent> event;

    private final List<MsqEvent> pendingArrivals = new ArrayList<>();

    private int numberJobInSystem = 0;
    private final int RIDESERVERS;

    private final double P_EXIT;
    private final double P_FEEDBACK;
    private final double P_MATCH_BUSY;
    private final double P_MATCH_IDLE;
    private final double TIME_WINDOW;
    private final List<RideSharingMultiServerNodeSimple> centriTradizionali;

    public RideSharingMultiServerNode(Sistema system, Rngs rng, List<RideSharingMultiServerNodeSimple> centriTradizionali) {

        this.rng = rng;
        this.centriTradizionali = centriTradizionali;

        ConfigurationManager config = new ConfigurationManager();
        P_EXIT = config.getDouble("probabilities", "rideExit");
        P_FEEDBACK = config.getDouble("probabilities", "rideFeedback");
        P_MATCH_BUSY = config.getDouble("probabilities", "rideMatchBusy");
        P_MATCH_IDLE = config.getDouble("probabilities", "rideMatchIdle");
        TIME_WINDOW = config.getDouble("simulation", "timeWindow");

        String[] srv = config.getString("simulation", "rideSimpleServers").split(",");
        int small = Integer.parseInt(srv[0].trim());
        var medium = Integer.parseInt(srv[1].trim());
        RIDESERVERS = config.getInt("simulation", "rideServers");

        sum = new MsqSum[RIDESERVERS + 1];
        event = new ArrayList<>(RIDESERVERS + 1);

        for (int i = 0; i <= RIDESERVERS; i++) {

            MsqEvent ev = new MsqEvent();

            if (i > 0) {
                if (i <= small) ev.capacita = ev.capacitaRimanente = 3;
                else if (i <= small + medium) ev.capacita = ev.capacitaRimanente = 4;
                else ev.capacita = ev.capacitaRimanente = 8;
                ev.numRichiesteServite = 0;
                ev.x = 0;
            } else {
                ev.t = distrs.getNextArrivalTimeRideSharing(rng, clock.current);
                ev.x = 1;
            }
            sum[i] = new MsqSum();
            event.add(ev);
        }
        resetState();
    }

    @Override
    public double peekNextEventTime() {
        double tmin = Double.POSITIVE_INFINITY;
        for (int i = 0; i <= RIDESERVERS; i++) {
            MsqEvent ev = event.get(i);
            if (ev.x == 1 && ev.t < tmin) tmin = ev.t;
        }
        return tmin;
    }

    @Override
    public int peekNextEventType() {
        int best = -1;
        double tmin = Double.POSITIVE_INFINITY;
        for (int i = 0; i <= RIDESERVERS; i++) {
            MsqEvent ev = event.get(i);
            if (ev.x == 1 && ev.t < tmin) {
                tmin = ev.t;
                best = i;
            }
        }
        return best;
    }

    // Campo di classe:
    private double nextMatchTime = Double.POSITIVE_INFINITY;

    @Override
    public int processNextEvent(double t) {
        int e = peekNextEventType();
        clock.current = t;

        if (e == ARRIVAL) {
            // 1) ARRIVAL
            numberJobInSystem++;

            // Schedule next ARRIVAL
            MsqEvent arr = new MsqEvent();
            arr.t = distrs.getNextArrivalTimeRideSharing(rng, clock.current);
            arr.x = 1;
            arr.postiRichiesti = getNumPosti();
            event.set(ARRIVAL, arr);

            rng.selectStream(2);
            double p = rng.random();
            if (p < P_EXIT) {
                numberJobInSystem--;
                return -1;
            } else if (p < P_FEEDBACK) {
                numberJobInSystem--;
                generateFeedback(arr);
                return -1;
            }

            // 2) Accumulo in coda
            pendingArrivals.add(arr);

            // 3) Imposto finestra se prima
            if (Double.isInfinite(nextMatchTime)) {
                nextMatchTime = clock.current + TIME_WINDOW;
            }

        } else {
            // 4) DEPARTURE

            /*aggiornamento dei valori*/
            MsqEvent sEvent = event.get(e);
            int numRichiesteServite = sEvent.numRichiesteServite;

            numberJobInSystem -= numRichiesteServite;
            sum[e].served += numRichiesteServite;

            /*aumentiamo il tempo di servizio*/
            sum[e].service += event.get(e).svc;

            sEvent.x = 0;
            sEvent.capacitaRimanente   = sEvent.capacita;
            sEvent.numRichiesteServite = 0;
            sEvent.postiRichiesti      = 0;

            return e;
        }

        // 5) Batch‑matching
        if (clock.current >= nextMatchTime) {
            while (true) {
                int matched = findOne();
                if (matched == 0) {
                    if (!pendingArrivals.isEmpty()) {
                        numberJobInSystem --;
                        MsqEvent toFb = pendingArrivals.getFirst();
                        generateFeedback(toFb);
                        pendingArrivals.removeFirst();
                        /*aggiunta per prova*/
                        continue;
                        /*aggiunta per prova*/
                    }
                    break;
                }
            }
            nextMatchTime = Double.POSITIVE_INFINITY;
        }

        

        return -1;
    }

    @Override
    public void integrateTo(double t) {
        if (t <= clock.current) return;
        double dt = t - clock.current; /*intervallo di tempo da integrare*/
        areaCollector.incNodeArea(dt * numberJobInSystem);

        int busy = (int) Math.round(getBusy());
        areaCollector.incServiceArea(dt * busy);
        // 4. Incremento area di coda (solo quelle *in attesa*)
        int inQueue = Math.max(0, numberJobInSystem - busy);
        areaCollector.incQueueArea(dt * inQueue);
        clock.current = t;
    }

    public double getBusy() {
        int busy = 0;
        for (int i = 1; i < event.size(); i++) {
            busy += event.get(i).getNumRichiesteServite();
        }
        return busy;
    }

    @Override
    public Area getAreaObject() { return areaCollector; }

    @Override
    public MsqSum[] getMsqSums() { return sum; }

    @Override
    public void resetState() {
        numberJobInSystem = 0;
        clock.current = clock.next = 0.0;
        areaCollector.reset();
        pendingArrivals.clear();
        nextMatchTime = Double.POSITIVE_INFINITY;
        for (MsqSum s : sum) s.reset();
    }

    private int getNumPosti() {
        rng.selectStream(4);
        double r = rng.random();
        if (r < 0.4) return 1;
        if (r < 0.7) return 2;
        if (r < 0.9) return 3;
        return 4;
    }

    public double getUtilization() {
        double busyTime = areaCollector.getServiceArea();
        int servers = sum.length - 1;
        return (clock.current > 0 && servers > 0) ? busyTime / (servers * clock.current) : 0.0;
    }

    @Override
    public MsqServer[] getServersCompletition() {
        return new MsqServer[0];
    }

    @Override
    public void resetStatistics() {

        // reset delle aree
        areaCollector.reset();

        // reset dei sum statistici
        if (sum != null) {
            for (MsqSum sum : sum) {
                if (sum != null) {
                    sum.reset();
                }
            }
        }
    }

    public void generateFeedback(MsqEvent event) {

        int num_posti = event.postiRichiesti;
        if(num_posti <= 3){
            centriTradizionali.getFirst().generateArrival(event.t);
            //genera evento di tipo 1
        } else if(num_posti == 4){
            centriTradizionali.get(1).generateArrival(event.t);
            //genera eventi di tipo 2
        } else {
            centriTradizionali.get(2).generateArrival(event.t);
            //genera eventi di tipo 3
        }
    }

    public int findOne() {
        if (pendingArrivals.isEmpty()) return 0;

        // 1. Prendo la PRIMA richiesta in coda
        MsqEvent firstReq = pendingArrivals.getFirst();

        // 2. CERCO best‑fit tra i server *attivi*
        int bestActive = -1;
        double bestCapActive = -1;

        rng.selectStream(3);
        for (int i = 1; i <= RIDESERVERS; i++) {
            if (event.get(i).x == 1
                    && event.get(i).capacitaRimanente>= firstReq.postiRichiesti
                    && rng.random() < P_MATCH_BUSY /*indica la probabilità che sto nel percorso giusto*/
                    && event.get(i).capacitaRimanente > bestCapActive) {
                bestCapActive = event.get(i).capacitaRimanente;
                bestActive = i;
            }
        }

        if (bestActive != -1) {
            // 2.a Assegno *solo* la prima richiesta a questo server
            assignToServer(bestActive, firstReq);
            pendingArrivals.removeFirst();
            return 1;
        }

        // 3. FALLBACK interno: best‑fit tra server *inattivi*
        int bestIdle = -1; double bestCapIdle = -1;
        rng.selectStream(4);
        for (int i = 1; i <= RIDESERVERS; i++) {
            if (event.get(i).x == 0
                    && event.get(i).capacitaRimanente >= firstReq.postiRichiesti
                    && rng.random() < P_MATCH_IDLE
                    && event.get(i).capacitaRimanente > bestCapIdle) {
                bestCapIdle = event.get(i).capacitaRimanente;
                bestIdle = i;
            }
        }
        if (bestIdle == -1) {
            return 0;  // né attivi né inattivi hanno accettato
        }

        // 3.a Attivo il server e *accorpo* quante richieste posso
        event.get(bestIdle).x = 1;
        int totalMatched = 0;
        Iterator<MsqEvent> it = pendingArrivals.iterator();
        while (it.hasNext()) {
            MsqEvent req = it.next();
            if (req.postiRichiesti <= event.get(bestIdle).capacitaRimanente) {
                assignToServer(bestIdle, req);
                it.remove();
                totalMatched++;
                if (event.get(bestIdle).capacitaRimanente == 0) break;
            }
        }
        return totalMatched; //totale di richieste matchate
    }

    private void assignToServer(int serverIdx, MsqEvent req) {
        MsqEvent s = event.get(serverIdx);
        double svcNew = distrs.getServiceTimeRideSharing(rng);

        //System.out.printf("=== Assigning request (postiRichiesti=%d) to server %d at time %.3f ===%n", req.postiRichiesti, serverIdx, clock.current);
        //System.out.printf("New service time drawn: %.3f%n", svcNew);

        double alpha = 0.2; // fattore di incremento (10% per ogni richiesta aggiuntiva)

        //System.out.printf("Server %d status: isBusy=%b, numRichiesteServite=%d%n", serverIdx, s.isBusy(), s.numRichiesteServite);
        //System.out.printf("Current service info before assignment: startServiceTime=%.3f, svc=%.3f, t=%.3f%n", s.startServiceTime, s.svc, s.t);

        if (!s.isBusy()) {
            // Primo passeggero → parte subito
            s.startServiceTime = clock.current;
            s.svc = svcNew;
            s.t = s.startServiceTime + svcNew;

            //System.out.printf("Server %d was idle. Starting service at %.3f, ending at %.3f%n",
              //      serverIdx, s.startServiceTime, s.t);
        } else {
            // Server già attivo
            double elapsed = clock.current - s.startServiceTime;
            double remaining = Math.max(s.svc - elapsed, 0);

            //System.out.printf("Elapsed time on current service: %.3f%n", elapsed);
            //System.out.printf("Remaining time on current service: %.3f%n", remaining);

            if (remaining < 1e-6) {
                // Servizio praticamente finito → resetto partenza da adesso
                s.startServiceTime = clock.current;
                s.svc = svcNew;
                s.t = s.startServiceTime + svcNew;

                //System.out.printf("Server %d service finished. Restarting at %.3f, ending at %.3f%n",
                  //      serverIdx, s.startServiceTime, s.t);
            } else {
                // Calcolo overhead proporzionale al numero di richieste già servite
                double overhead = svcNew * alpha * s.numRichiesteServite;
                //System.out.printf("Calculated overhead: svcNew * alpha * numRichiesteServite = %.3f * %.3f * %d = %.3f%n",
                  //      svcNew, alpha, s.numRichiesteServite, overhead);

                // Calcolo nuovo tempo di servizio: prendo il max tra remaining e svcNew+overhead per "allungare"
                double newServiceTime = svcNew + overhead;
                //System.out.printf("New service time (svcNew + overhead): %.3f + %.3f = %.3f%n", svcNew, overhead, newServiceTime);

                // Aggiorno tempo di servizio totale
                double maxTime = Math.max(remaining, newServiceTime);
                //System.out.printf("Max tra remaining e newServiceTime: max(%.3f, %.3f) = %.3f%n", remaining, newServiceTime, maxTime);

                //s.svc = elapsed + maxTime;
                s.svc = elapsed + (s.svc*s.numRichiesteServite + svcNew) / (s.numRichiesteServite+1);
                s.t = s.startServiceTime + s.svc;

                //System.out.printf("Server %d busy. Updated svc: %.3f, Updated end time: %.3f%n",
                  //      serverIdx, s.svc, s.t);
            }
        }

        // Aggiorna batch
        s.numRichiesteServite++;
        s.capacitaRimanente -= req.postiRichiesti;
        s.postiRichiesti += req.postiRichiesti;

        //System.out.printf("After assignment: Server %d now serving %d requests, capacitaRimanente: %d, postiRichiesti: %d%n%n",
          //      serverIdx, s.numRichiesteServite, s.capacitaRimanente, s.postiRichiesti);
    }
}
