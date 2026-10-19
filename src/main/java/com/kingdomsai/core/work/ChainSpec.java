package com.kingdomsai.core.work;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cadeia como a IA (ou um modelo pronto) descreve: papéis + etapas em texto. Ainda não validada —
 * o {@link ChainValidator} resolve quem faz, onde, e se a sequência fecha.
 */
public final class ChainSpec {
    public static final int MAX_STEPS = 12;

    public String name = "";
    public boolean repeat = true;
    /** papel → nome do NPC ou profissão. Papel sem entrada: a profissão vem do nome do papel ("ferreiro"). */
    public Map<String, String> roles = new LinkedHashMap<>();
    public List<StepSpec> steps = new ArrayList<>();

    public static final class StepSpec {
        public String role;
        public String type;
        public Map<String, String> params = new LinkedHashMap<>();

        public StepSpec() {}

        public StepSpec(String role, String type, String... kv) {
            this.role = role;
            this.type = type;
            for (int i = 0; i + 1 < kv.length; i += 2) params.put(kv[i], kv[i + 1]);
        }
    }

    public ChainSpec step(String role, String type, String... kv) {
        steps.add(new StepSpec(role, type, kv));
        return this;
    }
}
