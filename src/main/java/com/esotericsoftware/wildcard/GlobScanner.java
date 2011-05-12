package com.esotericsoftware.wildcard;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

class GlobScanner {
    private final File rootDir;
    private final List<String> matches = new ArrayList<String>(128);

    public GlobScanner(File rootDir, List<String> includes, List<String> excludes) {
        if (rootDir == null)
            throw new IllegalArgumentException("rootDir cannot be null.");
        if (!rootDir.exists())
            throw new IllegalArgumentException("Directory does not exist: " + rootDir);
        if (!rootDir.isDirectory())
            throw new IllegalArgumentException("File must be a directory: " + rootDir);
        try {
            rootDir = rootDir.getCanonicalFile();
        } catch (IOException ex) {
            throw new RuntimeException("OS error determining canonical path: " + rootDir, ex);
        }
        this.rootDir = rootDir;

        if (includes == null)
            throw new IllegalArgumentException("includes cannot be null.");
        if (excludes == null)
            throw new IllegalArgumentException("excludes cannot be null.");

        if (includes.isEmpty())
            includes.add("**");
        List<Pattern> includePatterns = new ArrayList<Pattern>(includes.size());
        for (String include : includes)
            includePatterns.add(new Pattern(include));

        List<Pattern> allExcludePatterns = new ArrayList<Pattern>(excludes.size());
        for (String exclude : excludes)
            allExcludePatterns.add(new Pattern(exclude));

        scanDir(rootDir, includePatterns);

        if (!allExcludePatterns.isEmpty()) {
            // For each file, see if any exclude patterns match.
            outerLoop:
            //
            for (Iterator<String> matchIter = matches.iterator(); matchIter.hasNext();) {
                String filePath = (String) matchIter.next();
                List<Pattern> excludePatterns = new ArrayList<Pattern>(allExcludePatterns);
                try {
                    // Shortcut for excludes that are "**/XXX", just check file
                    // name.
                    for (Iterator<Pattern> excludeIter = excludePatterns.iterator(); excludeIter.hasNext();)
                    {
                        Pattern exclude = (Pattern) excludeIter.next();
                        if (exclude.values.length == 2 && exclude.values[0].equals("**")) {
                            exclude.incr();
                            String fileName = filePath.substring(filePath
                                    .lastIndexOf(File.separatorChar) + 1);
                            if (exclude.matches(fileName)) {
                                matchIter.remove();
                                continue outerLoop;
                            }
                            excludeIter.remove();
                        }
                    }
                    // Get the file names after the root dir.
                    String[] fileNames = filePath.split("\\" + File.separator);
                    for (String fileName : fileNames) {
                        for (Iterator<Pattern> excludeIter = excludePatterns.iterator(); excludeIter
                                .hasNext();)
                        {
                            Pattern exclude = (Pattern) excludeIter.next();
                            if (!exclude.matches(fileName)) {
                                excludeIter.remove();
                                continue;
                            }
                            exclude.incr(fileName);
                            if (exclude.wasFinalMatch()) {
                                // Exclude pattern matched.
                                matchIter.remove();
                                continue outerLoop;
                            }
                        }
                        // Stop processing the file if none of the exclude
                        // patterns matched.
                        if (excludePatterns.isEmpty())
                            continue outerLoop;
                    }
                } finally {
                    for (Pattern exclude : allExcludePatterns)
                        exclude.reset();
                }
            }
        }
    }

    private void scanDir(File dir, List<Pattern> includes) {
        if (!dir.canRead())
            return;

        // See if patterns are specific enough to avoid scanning every file in
        // the directory.
        boolean scanAll = false;
        for (Pattern include : includes) {
            if (include.value.indexOf('*') != -1 || include.value.indexOf('?') != -1) {
                scanAll = true;
                break;
            }
        }

        if (!scanAll) {
            // If not scanning all the files, we know exactly which ones to
            // include.
            List<Pattern> matchingIncludes = new ArrayList<Pattern>(1);
            for (Pattern include : includes) {
                if (matchingIncludes.isEmpty())
                    matchingIncludes.add(include);
                else
                    matchingIncludes.set(0, include);
                process(dir, include.value, matchingIncludes);
            }
        } else {
            // Scan every file.
            for (String fileName : dir.list()) {
                // Get all include patterns that match.
                List<Pattern> matchingIncludes = new ArrayList<Pattern>(includes.size());
                for (Pattern include : includes)
                    if (include.matches(fileName))
                        matchingIncludes.add(include);
                if (matchingIncludes.isEmpty())
                    continue;
                process(dir, fileName, matchingIncludes);
            }
        }
    }

    private void process(File dir, String fileName, List<Pattern> matchingIncludes) {
        // Increment patterns that need to move to the next token.
        boolean isFinalMatch = false;
        List<Pattern> incrementedPatterns = new ArrayList<Pattern>();
        for (Iterator<Pattern> iter = matchingIncludes.iterator(); iter.hasNext();) {
            Pattern include = (Pattern) iter.next();
            if (include.incr(fileName)) {
                incrementedPatterns.add(include);
                if (include.isExhausted())
                    iter.remove();
            }
            if (include.wasFinalMatch())
                isFinalMatch = true;
        }

        File file = new File(dir, fileName);
        if (isFinalMatch) {
            int length = rootDir.getPath().length();
            if (length > 1)
                matches.add(file.getPath().substring(length + 1));
            else
                matches.add(file.getPath());
        }
        if (!matchingIncludes.isEmpty() && file.isDirectory())
            scanDir(file, matchingIncludes);

        // Decrement patterns.
        for (Pattern include : incrementedPatterns)
            include.decr();
    }

    public List<String> matches() {
        return matches;
    }

    public File rootDir() {
        return rootDir;
    }

    static class Pattern {
        String value;
        final String[] values;

        private int index;

        Pattern(String pattern) {
            pattern = pattern.replace('\\', '/');
            pattern = pattern.replaceAll("\\*\\*[^/]", "**/*");
            pattern = pattern.replaceAll("[^/]\\*\\*", "*/**");
            // pattern = pattern.toLowerCase();
            values = pattern.split("/");
            value = values[0];
        }

        boolean matches(String fileName) {
            if (value.equals("**"))
                return true;

            // fileName = fileName.toLowerCase();

            // Shortcut if no wildcards.
            if (value.indexOf('*') == -1 && value.indexOf('?') == -1)
                return fileName.equals(value);

            int i = 0, j = 0;
            while (i < fileName.length() && j < value.length() && value.charAt(j) != '*') {
                if (value.charAt(j) != fileName.charAt(i) && value.charAt(j) != '?')
                    return false;
                i++;
                j++;
            }

            // If reached end of pattern without finding a * wildcard, the match
            // has to fail if not same length.
            if (j == value.length())
                return fileName.length() == value.length();

            int cp = 0;
            int mp = 0;
            while (i < fileName.length()) {
                if (j < value.length() && value.charAt(j) == '*') {
                    if (j++ >= value.length())
                        return true;
                    mp = j;
                    cp = i + 1;
                } else if (j < value.length()
                        && (value.charAt(j) == fileName.charAt(i) || value.charAt(j) == '?'))
                {
                    j++;
                    i++;
                } else {
                    j = mp;
                    i = cp++;
                }
            }

            // Handle trailing asterisks.
            while (j < value.length() && value.charAt(j) == '*')
                j++;

            return j >= value.length();
        }

        String nextValue() {
            if (index + 1 == values.length)
                return null;
            return values[index + 1];
        }

        boolean incr(String fileName) {
            if (value.equals("**")) {
                if (index == values.length - 1)
                    return false;
                incr();
                if (matches(fileName))
                    incr();
                else {
                    decr();
                    return false;
                }
            } else
                incr();
            return true;
        }

        void incr() {
            index++;
            if (index >= values.length)
                value = null;
            else
                value = values[index];
        }

        void decr() {
            index--;
            if (index > 0 && values[index - 1].equals("**"))
                index--;
            value = values[index];
        }

        void reset() {
            index = 0;
            value = values[0];
        }

        boolean isExhausted() {
            return index >= values.length;
        }

        boolean isLast() {
            return index >= values.length - 1;
        }

        boolean wasFinalMatch() {
            return isExhausted() || (isLast() && value.equals("**"));
        }
    }
}