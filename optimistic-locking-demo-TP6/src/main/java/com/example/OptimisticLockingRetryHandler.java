package com.example;

import com.example.model.Reservation;
import com.example.service.ReservationService;

import javax.persistence.OptimisticLockException;
import java.util.Optional;
import java.util.function.Consumer;

public class OptimisticLockingRetryHandler {

    private final ReservationService reservationService;
    private final int maxRetries;

    public OptimisticLockingRetryHandler(ReservationService reservationService, int maxRetries) {
        this.reservationService = reservationService;
        this.maxRetries = maxRetries;
    }

    public void executeWithRetry(Long reservationId, Consumer<Reservation> operation) {
        int attempts = 0;
        boolean success = false;

        while (!success && attempts < maxRetries) {
            attempts++;
            try {
                Optional<Reservation> reservationOpt = reservationService.findById(reservationId);
                if (!reservationOpt.isPresent()) {
                    System.out.println("Réservation non trouvée !");
                    return;
                }

                Reservation reservation = reservationOpt.get();
                System.out.println("Tentative " + attempts + " : Réservation récupérée, version = " + reservation.getVersion());

                // Appliquer l'opération sur la réservation
                operation.accept(reservation);

                // Mettre à jour la réservation
                reservationService.update(reservation);

                success = true;
                System.out.println("Opération réussie après " + attempts + " tentative(s) !");
            } catch (OptimisticLockException e) {
                System.out.println("Tentative " + attempts + " : Conflit de verrouillage optimiste détecté !");

                if (attempts >= maxRetries) {
                    System.out.println("Nombre maximum de tentatives atteint. Abandon de l'opération.");
                    throw e;
                }

                // Attendre un peu avant de réessayer
                try {
                    Thread.sleep(100 * attempts); // Backoff exponentiel
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interruption pendant l'attente", ie);
                }
            }
        }
    }
}