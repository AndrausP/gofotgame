package com.kingdomsai.core.civic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Pedido de um súdito ao reino: vira uma ação real (ou um decreto) se o conselho/rei aprovar. */
public final class Petition {
    public enum Status { PENDING, APPROVED, REJECTED, EXPIRED }

    public UUID id = UUID.randomUUID();
    public int number;
    public UUID kingdomId;
    public UUID authorId;
    public String authorName = "";
    /** FOOD, HOUSING, TAX_DOWN, NO_CONSCRIPTION, GUARDS, CHAPEL, TAVERN, LIBRARY, DECREE. */
    public String kind = "";
    public String text = "";
    public String reason = "";
    /** Ação real que será executada se aprovada (nome de ActionType); vazio para decretos. */
    public String actionType = "";
    public Map<String, String> params = new LinkedHashMap<>();
    /** Para decretos: efeito mecânico (NONE, CURFEW, PEACE, FEAST). */
    public String effect = "NONE";
    public Status status = Status.PENDING;
    public long filedTick;
    public long decidedTick;
    public String verdict = "";
    public int minSupport = 1;

    public Petition() {}
}
