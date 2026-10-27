/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The builder's decision log (see the buildDebugLog setting): what it decided each tick and why, one file per build.
 * A tick's lines are written together; a tick exactly like the one before is only counted, so a bot doing the same
 * thing over and over shows up as one block of lines and how many ticks it went on for.
 */
final class BuilderLog {

    private static final long MAX_BYTES = 64L << 20;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private BufferedWriter out;
    private long written;
    private long tick;
    private final List<String> frame = new ArrayList<>();
    private List<String> lastFrame = new ArrayList<>();
    private int repeats;
    private long firstRepeat;
    private long lastRepeat;

    void open(Path file, String header) {
        close();
        try {
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            written = 0;
            lastFrame = new ArrayList<>();
            repeats = 0;
            write(header);
            out.flush();
        } catch (IOException e) {
            out = null;
        }
    }

    boolean isOpen() {
        return out != null;
    }

    void beginTick(long tick) {
        this.tick = tick;
        frame.clear();
    }

    void add(String line) {
        if (out != null) {
            frame.add(line);
        }
    }

    /**
     * For something that happened outside the tick's decisions (a click's result, a build ending): written right away
     */
    void event(long tick, String line) {
        if (out == null) {
            return;
        }
        flushRepeats();
        write(prefix(tick) + line);
        lastFrame = new ArrayList<>();
        flush();
    }

    void endTick() {
        if (out == null || frame.isEmpty()) {
            return;
        }
        if (frame.equals(lastFrame)) {
            if (repeats == 0) {
                firstRepeat = tick;
            }
            repeats++;
            lastRepeat = tick;
            return;
        }
        flushRepeats();
        String prefix = prefix(tick);
        for (String line : frame) {
            write(prefix + line);
        }
        lastFrame = new ArrayList<>(frame);
        flush();
    }

    void close() {
        if (out == null) {
            return;
        }
        flushRepeats();
        try {
            out.close();
        } catch (IOException ignored) {}
        out = null;
    }

    private void flushRepeats() {
        if (repeats > 0) {
            write("    (same again " + repeats + "x, ticks " + firstRepeat + ".." + lastRepeat + ")");
            repeats = 0;
        }
    }

    private String prefix(long tick) {
        return LocalTime.now().format(TIME) + " t" + tick + " ";
    }

    private void write(String line) {
        if (out == null) {
            return;
        }
        try {
            if (written > MAX_BYTES) {
                out.write("(log too big, stopped)\n");
                out.close();
                out = null;
                return;
            }
            out.write(line);
            out.write('\n');
            written += line.length() + 1;
        } catch (IOException e) {
            out = null;
        }
    }

    private void flush() {
        try {
            if (out != null) {
                out.flush();
            }
        } catch (IOException e) {
            out = null;
        }
    }
}
