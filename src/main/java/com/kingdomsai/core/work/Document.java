package com.kingdomsai.core.work;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Livro (fica na biblioteca, qualquer um alfabetizado pode ler) ou carta (vai até o destinatário). */
public final class Document {
    public enum Kind { BOOK, LETTER }

    public UUID id;
    public UUID kingdomId;
    public Kind kind = Kind.BOOK;
    public String title = "";
    public String text = "";
    /** Frases curtas que o leitor aprende (viram memórias de quem lê). */
    public List<String> facts = new ArrayList<>();
    public UUID authorId;
    public String authorName = "";
    public UUID recipientId;
    public UUID libraryId;
    public long tick;
    public boolean delivered;
    public List<UUID> readers = new ArrayList<>();
}
