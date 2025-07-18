package org.uniroma2.PMCSN.utils;

import org.uniroma2.PMCSN.libs.Rvms;

import static org.uniroma2.PMCSN.libs.Distributions.idfStandard;

public class SampleSizeEstimator {

    /**
     * Stima n dato un guess per la deviazione standard s, la mezza ampiezza w
     * e il livello di confidenza (es. 0.95).
     * Usa iterazione su t di Student per rifinire la stima.
     */
    public long estimateSampleSize(double s, double w, double confidenceLevel) {

        Rvms rvms = new Rvms();

        double alpha2 = (1.0 - confidenceLevel) / 2.0;
        // 1) prima approssimazione con la normale Z
        double z = idfStandard(1.0 - alpha2);
        double n = Math.pow(z * s / w, 2) + 1.0;

        // 2) itero fino a convergenza sul quantile t di Student
        for (int i = 0; i < 100; i++) {
            double df = Math.max(1.0, n - 1.0);
            double t = rvms.idfStudent((long)df, 1.0 - alpha2);
            double nNew = Math.pow(t * s / w, 2) + 1.0;
            if (Math.abs(nNew - n) < 1e-6) {
                n = nNew;
                break;
            }
            n = nNew;
        }

        // arrotondo per eccesso e garantisco n>40
        long nFinal = (long)Math.ceil(n);
        return Math.max(nFinal, 41);
    }

    /**
     * Stima n quando non si ha una stima di s, ma si sceglie w come proporzione p di s:
     *   w = p * s  =>  n = (t_{1-α/2, n-1} / p)^2 + 1
     */
    public long estimateSampleSizeProportion(double wProportion, double confidenceLevel) {

        Rvms rvms = new Rvms();

        double alpha2 = (1.0 - confidenceLevel) / 2.0;
        double z = idfStandard(1.0 - alpha2);
        double n = Math.pow(z / wProportion, 2) + 1.0;

        for (int i = 0; i < 100; i++) {
            double df = Math.max(1.0, n - 1.0);
            double t = rvms.idfStudent((long)df, 1.0 - alpha2);
            double nNew = Math.pow(t / wProportion, 2) + 1.0;
            if (Math.abs(nNew - n) < 1e-6) {
                n = nNew;
                break;
            }
            n = nNew;
        }

        long nFinal = (long)Math.ceil(n);
        return Math.max(nFinal, 41);
    }

    /**
     * Stima rapida di n usando solo il quantile z della normale,
     * come nell'esempio delle slide (n ≈ (z / w)^2 + 1).
     *
     * @param wProportion  w come proporzione di s (es. 0.1 per 10%)
     * @param confidence   livello di confidenza (es. 0.95)
     * @return             numero di repliche n
     */
    public long estimateSampleSizeProportionFast(double wProportion, double confidence) {


        Rvms rvms = new Rvms();

        double alpha2 = (1.0 - confidence) / 2.0;
        // z quantile della normale standard
        double z = rvms.idfStandard(1.0 - alpha2);
        // formula diretta
        double n = Math.pow(z / wProportion, 2) + 1.0;
        return (long) Math.ceil(n);
    }

    /**
     * Data una proporzione wProportion (= w/s) e livello di confidenza,
     * calcola n per ogni stima preliminare di s in preStd e restituisce
     * il massimo tra tutti i n calcolati.
     *
     * @param wProportion      w come proporzione di s (es. 0.1)
     * @param confidenceLevel  livello di confidenza (es. 0.95)
     * @param preStd           array di stime preliminari di sigma per ogni variabile
     * @return                 il numero di repliche da usare (il più grande tra tutti)
     */
    public long estimateOverallSampleSize(
            double wProportion,
            double confidenceLevel,
            double... preStd
    ) {
        long maxN = 0;
        // per ogni stima preliminare di sigma
        for (double s : preStd) {
            // 1) calcolo n_i per quella s: usa l’iterativo o il fast
            long n_i = estimateSampleSize(s, wProportion * s, confidenceLevel);
            // se preferisci la versione fast:
            // long n_i = estimateSampleSizeProportionFast(wProportion, confidenceLevel);
            // 2) aggiorna il massimo
            if (n_i > maxN) {
                maxN = n_i;
            }
        }
        return maxN;
    }

}