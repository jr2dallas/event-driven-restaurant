package fr.jr2dallas.domains;

public enum SessionState {
    QUEUING,         // en file d'attente
    AT_ENTRANCE,     // arrivé à l'entrée, en attente d'un siège
    WALKING_TO_SEAT, // le back a assigné une chaise, le front anime le trajet
    SEATED,        // assis (le front anime ENTERING + WALKING_TO_SEAT)
    WAITING_ORDER, // a commandé
    EATING,        // mange
    LEAVING,       // part
    DESPAWNED      // session terminée
}