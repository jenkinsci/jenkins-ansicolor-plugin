package hudson.plugins.ansicolor.action;

import hudson.Extension;
import hudson.console.ConsoleNote;
import hudson.model.Run;
import hudson.model.listeners.RunListener;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ShortlogActionCreator {
    private static final Logger LOGGER = Logger.getLogger(ShortlogActionCreator.class.getName());
    private static final int CONSOLE_TAIL_DEFAULT = 150;
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final byte[] EOL = System.lineSeparator().getBytes();

    private final LineIdentifier lineIdentifier;

    public ShortlogActionCreator(LineIdentifier lineIdentifier) {
        this.lineIdentifier = lineIdentifier;
    }

    public ColorizedAction createActionForShortlog(File logFile, Map<String, ColorizedAction> startActions, int beginFromEnd) {
        final ActionContext shortlogStartAction = findStartActionAt(logFile, startActions.keySet(), beginFromEnd);
        if (!shortlogStartAction.isEmpty()) {
            return new ColorizedAction(lineIdentifier.hash(ConsoleNote.removeNotes(shortlogStartAction.line), 1), startActions.get(shortlogStartAction.serializedAction));
        }
        return null;
    }

    private ActionContext findStartActionAt(File logFile, Collection<String> serializedActions, int bytesFromEnd) {
        try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(logFile))) {
            final long shortlogStart = logFile.length() - bytesFromEnd * 1024L;
            final byte[] buf = new byte[BUFFER_SIZE];
            int read;
            int totalRead = 0;
            String currentStartAction = "";
            byte[] partialLine = new byte[]{};
            while ((read = inputStream.read(buf)) != -1) {
                final String newAction = findActionInBuffer(serializedActions, buf);
                if (!newAction.isEmpty()) {
                    currentStartAction = newAction;
                }
                if (totalRead + read >= shortlogStart) {
//                    final String s = System.lineSeparator();
//                    final byte nl = (byte) '\n';
                    final int startInBuff = shortlogStart > totalRead ? (int) (shortlogStart - totalRead) : 0;
                    final int eolPos = indexOfEol(buf, /*nl,*/ startInBuff);
                    if (eolPos != -1) {
                        return new ActionContext(currentStartAction, new String(partialLine) + new String(buf, startInBuff, eolPos - startInBuff + EOL.length));
                    } else {
                        // line extends to the next buffer
                        partialLine = Arrays.copyOfRange(buf, startInBuff, buf.length - 1);
                    }
                }
                totalRead += read;
            }
        } catch (IOException e) {
            LOGGER.warning("Cannot search log for actions: " + e.getMessage());
        }

/*        try (
            CountingInputStream inputStream = new CountingInputStream(new FileInputStream(logFile));
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream), BUFFER_SIZE);
        ) {
            String currentStartAction = "";
            final long shortlogStart = logFile.length() - bytesFromEnd * 1024L;
            int posInBuff = 0;
            int i = 0;
            if (shortlogStart > 0) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(ConsoleNote.PREAMBLE_STR)) {
                        final Optional<String> startAction = serializedActions.stream().filter(line::contains).findFirst();
                        if (startAction.isPresent()) {
                            currentStartAction = startAction.get();
                        }
                    }
                    final long readBytes = inputStream.getByteCount();
                    if (readBytes >= shortlogStart) {
                        i++;
                        final String logLine = line + "\n";
                        posInBuff += logLine.length();

//                        final byte[] lineBytes = line.getBytes();
//                        posInBuff += lineBytes.length + 1; // System.lineSeparator().length();

                        // !line does NOT have to start at (readBytes - BUFFER_SIZE)!

                        final long shortlogStartBuff = shortlogStart - (readBytes - BUFFER_SIZE);
                        if (posInBuff >= shortlogStartBuff) {
                            return new ActionContext(currentStartAction, logLine.substring((int) (posInBuff - shortlogStartBuff - 21)));

//                            return new ActionContext(currentStartAction, new String(Arrays.copyOfRange(lineBytes, (int) (readBytes - shortlogStart), lineBytes.length-1)));
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warning("Cannot search log for actions: " + e.getMessage());
        }*/
        return new ActionContext();
    }

    private String findActionInBuffer(Collection<String> serializedActions, byte[] buf) {
        int preamblePos = 0;
        while (preamblePos < buf.length && (preamblePos = ConsoleNote.findPreamble(buf, preamblePos, buf.length - preamblePos)) != -1) {
            final int begin = preamblePos;
            final Optional<String> startAction = serializedActions.stream().filter(sa -> buf.length - begin > sa.length() && sa.equals(new String(buf, begin, sa.length()))).findFirst();
            if (startAction.isPresent()) {
                return startAction.get();
            }
            preamblePos++;
        }
        return "";
    }


    private int indexOfEol(byte[] buf, /*byte needle,*/ int after) {
        for (int i = after; i < buf.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(buf, i, i + EOL.length), EOL)) {
                return i;
            }
//            if (buf[i] == needle) {
//                return i;
//            }
        }
        return -1;
    }

    @Extension
    public static class Listener extends RunListener<Run<?, ?>> {
        @Override
        public void onFinalized(Run<?, ?> run) {
            super.onFinalized(run);
            Map<String, ColorizedAction> startActions = run.getActions(ColorizedAction.class).stream()
                .filter(a -> a.getCommand().equals(ColorizedAction.Command.START))
                .collect(Collectors.toMap(a -> {
                    try {
                        return new ActionNote(a).encode();
                    } catch (IOException e) {
                        LOGGER.warning("Will not be able to identify all ColorizedActions: " + e.getMessage());
                    }
                    return "";
                }, Function.identity()));
            if (!startActions.isEmpty()) {
                final File logFile = new File(run.getRootDir(), "log");
                if (logFile.isFile()) {
                    final ShortlogActionCreator shortlogActionCreator = new ShortlogActionCreator(new LineIdentifier());
                    final String consoleTail = System.getProperty("hudson.consoleTailKB");
                    final ColorizedAction action = shortlogActionCreator.createActionForShortlog(logFile, startActions, consoleTail != null ? Integer.parseInt(consoleTail) : CONSOLE_TAIL_DEFAULT);
                    if (action != null) {
                        run.addAction(action);
                    }
                }
            }
        }
    }

    private static class ActionContext {
        private final String serializedAction;
        private final String line;

        public ActionContext() {
            this(null, null);
        }

        public ActionContext(String serializedAction, String line) {
            this.serializedAction = serializedAction;
            this.line = line;
        }

        public boolean isEmpty() {
            return serializedAction == null && line == null;
        }
    }
}