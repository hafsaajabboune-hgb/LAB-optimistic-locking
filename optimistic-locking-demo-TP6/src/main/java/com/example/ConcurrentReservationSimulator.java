package com.example;

import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.Utilisateur;
import com.example.service.ReservationService;
import com.example.service.ReservationServiceImpl;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ConcurrentReservationSimulator {

    private static final Logger logger = Logger.getLogger(ConcurrentReservationSimulator.class.getName());
    private static final EntityManagerFactory emf =
            Persistence.createEntityManagerFactory("optimistic-locking-demo");
    private static final ReservationService reservationService = new ReservationServiceImpl(emf);

    public static void main(String[] args) throws InterruptedException {
        initData();
        System.out.println("\n=== Simulation d'un conflit sans retry ===");
        simulateConcurrentReservationConflict();

        initData();
        System.out.println("\n=== Simulation d'un conflit avec retry ===");
        simulateConcurrentReservationConflictWithRetry();

        emf.close();
    }

    private static void initData() {
        EntityManager em = null;
        EntityTransaction tx = null;

        try {
            em = emf.createEntityManager();
            tx = em.getTransaction();
            tx.begin();

            em.createQuery("DELETE FROM Reservation").executeUpdate();
            em.createQuery("DELETE FROM Utilisateur").executeUpdate();
            em.createQuery("DELETE FROM Salle").executeUpdate();

            em.createNativeQuery("ALTER TABLE utilisateurs ALTER COLUMN id RESTART WITH 1").executeUpdate();
            em.createNativeQuery("ALTER TABLE salles ALTER COLUMN id RESTART WITH 1").executeUpdate();
            em.createNativeQuery("ALTER TABLE reservations ALTER COLUMN id RESTART WITH 1").executeUpdate();

            Utilisateur utilisateur1 = new Utilisateur("Dupont", "Jean", "jean.dupont@example.com");
            Utilisateur utilisateur2 = new Utilisateur("Martin", "Sophie", "sophie.martin@example.com");
            Salle salle = new Salle("Salle A101", 30);
            salle.setDescription("Salle de réunion équipée d'un projecteur");

            em.persist(utilisateur1);
            em.persist(utilisateur2);
            em.persist(salle);

            Reservation reservation = new Reservation(
                    LocalDateTime.now().plusDays(1).withHour(10).withMinute(0),
                    LocalDateTime.now().plusDays(1).withHour(12).withMinute(0),
                    "Réunion d'équipe"
            );
            reservation.setUtilisateur(utilisateur1);
            reservation.setSalle(salle);

            em.persist(reservation);

            tx.commit();
            System.out.println("Données initialisées avec succès !");
            System.out.println("Réservation créée : " + reservation);
        } catch (Exception e) {
            if (tx != null && tx.isActive()) {
                tx.rollback();
            }
            logger.log(Level.SEVERE, "Erreur lors de l'initialisation", e);
        } finally {
            if (em != null && em.isOpen()) {
                em.close();
            }
        }
    }

    private static void simulateConcurrentReservationConflict() throws InterruptedException {
        Optional<Reservation> reservationOpt = reservationService.findById(1L);
        if (!reservationOpt.isPresent()) {
            System.out.println("Réservation non trouvée !");
            return;
        }

        Reservation reservation = reservationOpt.get();
        System.out.println("Réservation récupérée : " + reservation);

        CountDownLatch latch = new CountDownLatch(1);

        Thread thread1 = new Thread(() -> {
            try {
                latch.await();
                Optional<Reservation> r1Opt = reservationService.findById(1L);
                if (r1Opt.isPresent()) {
                    Reservation r1 = r1Opt.get();
                    System.out.println("Thread 1 : Réservation récupérée, version = " + r1.getVersion());
                    Thread.sleep(1000);
                    r1.setMotif("Réunion d'équipe modifiée par Thread 1");
                    try {
                        reservationService.update(r1);
                        System.out.println("Thread 1 : Réservation mise à jour avec succès !");
                    } catch (OptimisticLockException e) {
                        System.out.println("Thread 1 : Conflit de verrouillage optimiste détecté !");
                    }
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Erreur dans thread 1", e);
            }
        });

        Thread thread2 = new Thread(() -> {
            try {
                latch.await();
                Optional<Reservation> r2Opt = reservationService.findById(1L);
                if (r2Opt.isPresent()) {
                    Reservation r2 = r2Opt.get();
                    System.out.println("Thread 2 : Réservation récupérée, version = " + r2.getVersion());
                    r2.setDateDebut(r2.getDateDebut().plusHours(1));
                    r2.setDateFin(r2.getDateFin().plusHours(1));
                    try {
                        reservationService.update(r2);
                        System.out.println("Thread 2 : Réservation mise à jour avec succès !");
                    } catch (OptimisticLockException e) {
                        System.out.println("Thread 2 : Conflit de verrouillage optimiste détecté !");
                    }
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Erreur dans thread 2", e);
            }
        });

        thread1.start();
        thread2.start();
        latch.countDown();
        thread1.join();
        thread2.join();

        Optional<Reservation> finalReservationOpt = reservationService.findById(1L);
        if (finalReservationOpt.isPresent()) {
            Reservation r = finalReservationOpt.get();
            System.out.println("\nÉtat final de la réservation :");
            System.out.println("ID : " + r.getId());
            System.out.println("Motif : " + r.getMotif());
            System.out.println("Date début : " + r.getDateDebut());
            System.out.println("Date fin : " + r.getDateFin());
            System.out.println("Version : " + r.getVersion());
        }
    }

    private static void simulateConcurrentReservationConflictWithRetry() throws InterruptedException {
        OptimisticLockingRetryHandler retryHandler = new OptimisticLockingRetryHandler(reservationService, 3);
        CountDownLatch latch = new CountDownLatch(1);

        Thread thread1 = new Thread(() -> {
            try {
                latch.await();
                retryHandler.executeWithRetry(1L, r -> {
                    System.out.println("Thread 1 : Modification du motif");
                    r.setMotif("Réunion d'équipe modifiée par Thread 1");
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                System.out.println("Thread 1 : Modification réussie !");
            } catch (Exception e) {
                System.out.println("Thread 1 : Exception finale : " + e.getMessage());
            }
        });

        Thread thread2 = new Thread(() -> {
            try {
                latch.await();
                retryHandler.executeWithRetry(1L, r -> {
                    System.out.println("Thread 2 : Modification des dates");
                    r.setDateDebut(r.getDateDebut().plusHours(1));
                    r.setDateFin(r.getDateFin().plusHours(1));
                });
                System.out.println("Thread 2 : Modification réussie !");
            } catch (Exception e) {
                System.out.println("Thread 2 : Exception finale : " + e.getMessage());
            }
        });

        thread1.start();
        thread2.start();
        latch.countDown();
        thread1.join();
        thread2.join();

        Optional<Reservation> finalReservationOpt = reservationService.findById(1L);
        finalReservationOpt.ifPresent(r -> {
            System.out.println("\nÉtat final de la réservation avec retry :");
            System.out.println("ID : " + r.getId());
            System.out.println("Motif : " + r.getMotif());
            System.out.println("Date début : " + r.getDateDebut());
            System.out.println("Date fin : " + r.getDateFin());
            System.out.println("Version : " + r.getVersion());
        });
    }
}