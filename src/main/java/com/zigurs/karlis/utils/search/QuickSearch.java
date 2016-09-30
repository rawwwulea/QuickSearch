/*
 * Copyright 2016 Karlis Zigurs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zigurs.karlis.utils.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Simple and lightweight in-memory quick search provider.
 * <p>
 * Fit for low latency querying of small to medium sized datasets (limited by memory) to enable users
 * immediately see the top hits for their partially entered search string. Based on production experience
 * this approach is well perceived by users and their ability to see the top hits immediately allows
 * them to adjust their queries on the fly getting to the desired result faster.
 * <p>
 * By implementing this functionality directly in the app or corresponding backend the overall complexity of the
 * project can be significantly reduced - there is no need to care about maintaining search infrastructure, servers,
 * software or APIs.
 * <p>
 * Example uses can include:
 * <ul>
 * <li>Selecting from a list of existing contacts</li>
 * <li>Looking for a particular city (associating it with known aliases, landmarks, state, etc)</li>
 * <li>Searching for an item in an online (book) shop</li>
 * <li>Used in background to highlight items that match the (partial) keywords entered. A.la. OSX System Preferences search</li>
 * <li>Navigating large navigation trees, in example all sporting events for a year</li>
 * </ul>
 * <p>
 * Typical use case would be including it in ether application or a web server, maintaining the
 * data set (ether via provided add and remove methods or by clearing and repopulating the search contents
 * completely) and exposing an API to user that accepts a free-form input and returns corresponding matching items.
 * <p>
 * Each entry is associated with a number of keywords that are not exposed to user, therefore it is possible to add
 * name aliases or item class descriptions to keywords. Same applies to letting users discover items by unique identifiers
 * or alternate spellings.
 * <p>
 * An example contacts list is provided as example (entry followed by assigned keywords):
 * <table summary="">
 * <tr><th>Item</th><th>Supplied keywords</th></tr>
 * <tr><td>"Jane Doe, 1234"</td><td>"Jane Doe Marketing Manager SEO Community MySpace 1234"</td></tr>
 * <tr><td>"Alice Stuggard, 9473"</td><td>"Alice Stuggard Tech Cryptography Manager RSA 9473"</td></tr>
 * <tr><td>"Robert Howard, 6866"</td><td>"Robert Bob Howard Tech Necromancy Summoning Undead Cryptography BOFH RSA DOD Laundry 6866"</td></tr>
 * <tr><td>"Eve Moneypenny, 9223"</td><td>"Eve Moneypenny Accounting Manager Q OSA 9223"</td></tr>
 * </table>
 * <p>
 * In the example above if the user enters <code><strong>"Mana"</strong></code> he will be served a list of Jane,
 * Alice and Eve as their keyword <code><strong>"Manager"</strong></code> is matched by
 * <code><strong>"Mana"</strong></code>. Now user should see that the result set is sufficiently narrow and
 * can tailor his search further by continuing on to type <code><strong>"Mana a"</strong></code> - which will lead
 * to Alice and Eve being promoted to top of results. Alice because of her name match and Eve because of her department.
 * <code><strong>"Mana acc"</strong></code> will narrow the results to Eve only as she is only one in the search set
 * that can match both <code><strong>*mana*</strong></code> and <code><strong>*acc*</strong></code>.
 * <p>
 * Example use:
 * <p>
 * <code>QuickSearch&lt;String&gt; qs = new QuickSearch&lt;&gt;();<br/>
 * qs.addItem("Villain", "Roy Batty Lord Voldemort Colonel Kurtz");<br/>
 * qs.addItem("Hero", "Walt Kowalksi Jake Blues Shaun");<br/>
 * System.out.println(qs.findItem("walk")); // finds "Hero"</code>
 * <p>
 * Concurrency - This class is thread safe (public functions are synchronised). Implementation is completely passive
 * and can be deployed horizontally as identical datasets will produce identical search results.
 *
 * @author Karlis Zigurs, 2016
 */
public class QuickSearch<T> {

    /**
     * Interface to 'clean up' supplied keyword and user input strings. We assume that the input is
     * going to be ether free form or malformed, therefore this allows to apply required actions to generate
     * a 'clean' set of keywords from the input string.
     */
    public interface KeywordsExtractor {

        /**
         * Convert the input string into a list of keywords to be used internally.
         *
         * @param inputString supplied keywords or search input string
         * @return Set of extracted keywords, can be empty if no viable keywords could be extracted.
         */
        @NotNull
        Set<String> extract(@NotNull String inputString);
    }

    /**
     * Default raw input keywords extractor. Replaces all non-word characters with whitespace and
     * splits the resulting string on whitespace boundaries.
     * <p>
     * In example both "one two,three-four" and "one$two%three^four" as inputs will produce
     * set of 4 strings [one,two,three,four] on the output.
     */
    public static final KeywordsExtractor DEFAULT_KEYWORDS_EXTRACTOR = (s) -> new HashSet<>(Arrays.asList(s.replaceAll("[^\\w]+", " ").split("[\\s]+")));

    /**
     * Interface to sanitize search keywords before using them internally. Applied to both keywords
     * supplied with items and to user input before performing search.
     * <p>
     * Rationale is to allow somewhat relaxed free-form text input (e.g. phone devices automatically capitalising
     * entered keywords) and extra capability to remap special characters to their latin alphabet equivalents.
     * <p>
     * The normalized representation has no specific requirements, this is just a convenience method.
     * Simply returning the supplied string will mean that the search results contain only exact (and case
     * sensitive) matches. It is also possible to return empty strings here, in which case the supplied
     * keyword will be ignored.
     * <p>
     * Example transformations:
     * <table summary="">
     * <tr><th>Original</th><th>Transformed</th><th>Reason</th></tr>
     * <tr><td><code>"New York"</code></td><td><code>"new york"</code></td><td>remove upper case</td></tr>
     * <tr><td><code>"Pythøn"</code></td><td><code>"python"</code></td><td>replace special characters</td></tr>
     * <tr><td><code>"HERMSGERVØRDENBRØTBØRDA"</code></td><td><code>"hermsgervordenbrotborda"</code></td><td>it could happen...</td></tr>
     * <tr><td><code>"Россия"</code></td><td><code>"rossiya"</code></td><td>translate cyrilic alphabet to latin</td></tr>
     * </table>
     * <p>
     * Default implementation assumes that String::toLowerCase() is sufficient.
     */
    public interface KeywordNormalizer {

        /**
         * Called to request a final representation for the supplied keyword to be used internally.
         *
         * @param keyword original keyword
         * @return form to use internally or empty string if this keyword is to be ignored
         */
        @NotNull
        String normalize(@NotNull String keyword);
    }

    public static final KeywordNormalizer DEFAULT_KEYWORD_NORMALIZER = String::toLowerCase;

    /**
     * Interface providing scoring of user supplied input against corresponding keywords associated with search items.
     * <p>
     * An example invocations might request to compare <code><strong>"swe"</strong></code> against
     * <code><strong>"sweater"</strong></code> or <code><strong>"count"</strong></code> aganst
     * <code><strong>"accounting"</strong></code>.
     */
    public interface KeywordMatchScorer {
        /**
         * Score how well (likely incomplete) user input scores against identified matching item keyword.
         * <p>
         * Called multiple times for all user supplied strings against their matching keywords
         * associated with item and summed up to determine final item rank.
         *
         * @param keywordSubstring user supplied (partial) match as recognized internally
         * @param itemKeyword      full matching keyword associated with the item
         * @return arbitrary number scoring the match. Higher means closer match.
         */
        double score(@NotNull String keywordSubstring, @NotNull String itemKeyword);
    }

    /**
     * Default keyword match score implementation.
     * <p>
     * Returns the ratio between search term and keyword lengths with additional boost
     * if the search term matches beginning of the keyword.
     * <p>
     * In example, while matching user input against known keyword "password", the following will be calculated:
     * <ul>
     * <li>Input "pa" -&gt; low match (0.25), but boosted (+1) due to matching start of the keyword.</li>
     * <li>Input "swo" -&gt; low match (0.37), not boosted</li>
     * <li>Input "assword" -&gt; high match (0.87), not boosted</li>
     * <li>Input "password" -&gt; high match (1), also boosted by matching the beginning of the line (+1)</li>
     * </ul>
     * <p>
     * All keywords supplied by user are scored against all matching keywords associated with a searchable item.
     * Items rank in the results is determined by the sum of all score results.
     */
    public static final KeywordMatchScorer DEFAULT_MATCH_SCORER = (keywordMatch, keyword) -> {
        double matchScore = (double) keywordMatch.length() / (double) keyword.length(); // reaches maximum if lengths match (both are identical)

        // bonus boost for start of term
        if (keyword.startsWith(keywordMatch))
            matchScore += 1.0;

        return matchScore;
    };

    /**
     * Alternative match scorer which strongly prefers longer matches between the candidate and target.
     */
    public static final KeywordMatchScorer LENGTH_MATCH_SCORER = ((keywordSubstring, itemKeyword) -> {
        int score = keywordSubstring.length();

        if (itemKeyword.startsWith(keywordSubstring))
            score *= 2;

        return score;
    });

    /**
     * Container for augumented search results (including item scores and
     * intersect of keywords for all items).
     *
     * @param <RT> wrapped response item type
     */
    public static final class Response<RT> {

        public static final class Item<I> {
            @NotNull
            private final I item;
            @NotNull
            private final Set<String> itemKeywords;

            private final double score;

            public Item(@NotNull I item, @NotNull Collection<String> itemKeywords, double score) {
                this.item = item;
                this.itemKeywords = new HashSet<>(itemKeywords);
                this.score = score;
            }

            @NotNull
            public I getItem() {
                return item;
            }

            @NotNull
            public Set<String> getItemKeywords() {
                return itemKeywords;
            }

            public double getScore() {
                return score;
            }
        }

        @NotNull
        private final String searchString;
        @NotNull
        private final Collection<String> searchStringKeywords;
        private Collection<String> intersectingKeywords;

        @NotNull
        private final List<Item<RT>> responseItems;

        public Response(@NotNull String searchString, @NotNull Collection<String> searchStringKeywords, @NotNull List<Item<RT>> responseItems) {
            this.searchString = searchString;
            this.searchStringKeywords = searchStringKeywords;
            this.responseItems = responseItems;
        }

        @NotNull
        public String getSearchString() {
            return searchString;
        }

        @NotNull
        public Collection<String> getSearchStringKeywords() {
            return searchStringKeywords;
        }

        @NotNull
        public List<Item<RT>> getResponseItems() {
            return responseItems;
        }

        @NotNull
        public synchronized Collection<String> getIntersectingKeywords() {
            if (intersectingKeywords != null)
                return intersectingKeywords;

            for (Item<RT> item : getResponseItems()) {
                if (intersectingKeywords == null) {
                    intersectingKeywords = new HashSet<>(item.getItemKeywords());
                } else {
                    intersectingKeywords.removeIf(i -> !item.getItemKeywords().contains(i));
                }
            }

            return intersectingKeywords;
        }
    }

    private final class ItemAndScoreWrapper implements Comparable<ItemAndScoreWrapper> {
        @NotNull
        private final T item;
        @NotNull
        private final Set<String> itemKeywords;
        private double score;

        ItemAndScoreWrapper(@NotNull T item, @NotNull Set<String> itemKeywords, double score) {
            this.item = item;
            this.itemKeywords = itemKeywords;
            this.score = score;
        }

        @NotNull
        ItemAndScoreWrapper incrementScoreBy(double add) {
            score += add;
            return this;
        }

        @NotNull
        T getItem() {
            return item;
        }

        @NotNull
        Set<String> getItemKeywords() {
            return itemKeywords;
        }

        double getScore() {
            return score;
        }

        @Override
        public int compareTo(@NotNull ItemAndScoreWrapper o) {
            return Double.compare(this.score, o.score);
        }
    }

    /*
     * Variables
     */

    @NotNull
    private final KeywordMatchScorer keywordMatchScorer;
    @NotNull
    private final KeywordNormalizer keywordNormalizer;
    @NotNull
    private final KeywordsExtractor keywordsExtractor;

    /**
     * Default for minimum keyword length. Any keywords shorter than this will be ignored internally.
     */
    public static final int DEFAULT_MINIMUM_KEYWORD_LENGTH = 2;
    private final int minimumKeywordLength;

    private final Map<String, Set<T>> keywordsToItemsMap = new HashMap<>();
    private final Map<String, Set<String>> substringsToKeywordsMap = new HashMap<>();
    private final Map<T, Set<String>> itemKeywordsMap = new HashMap<>();

    /*
     * Constructors
     */

    /**
     * Constructs a QuickSearch instance using defaults for keywords extractor, normaliser, match scorer and
     * minimum keyword length.
     */
    public QuickSearch() {
        this(DEFAULT_KEYWORDS_EXTRACTOR, DEFAULT_KEYWORD_NORMALIZER, DEFAULT_MATCH_SCORER, DEFAULT_MINIMUM_KEYWORD_LENGTH);
    }

    /**
     * Constructs a QuickSearch instance with the provided keyword processing implementations and specified minimum
     * keyword length.
     *
     * @param keywordsExtractor    Extractor. {@link  KeywordsExtractor}
     * @param keywordNormalizer    Normalizer. {@link  KeywordNormalizer}
     * @param keywordMatchScorer   Scorer. {@link  KeywordMatchScorer}
     * @param minimumKeywordLength Minimum length for keywords internally. Any keywords shorter than specified will be ignored. Should be at least 1
     */
    public QuickSearch(@Nullable KeywordsExtractor keywordsExtractor, @Nullable KeywordNormalizer keywordNormalizer, @Nullable KeywordMatchScorer keywordMatchScorer, int minimumKeywordLength) {
        if (keywordsExtractor == null || keywordNormalizer == null || keywordMatchScorer == null || minimumKeywordLength < 1)
            throw new RuntimeException("Null instances or invalid minimum length supplied.");

        this.keywordsExtractor = keywordsExtractor;
        this.keywordNormalizer = keywordNormalizer;
        this.keywordMatchScorer = keywordMatchScorer;
        this.minimumKeywordLength = minimumKeywordLength;
    }

    /*
     * Public interface
     */

    /**
     * Add an item with corresponding keywords, e.g. an online store item Shoe with
     * keywords <code><strong>"Shoe Red 10 Converse cheap free"</strong></code>.
     * <p>
     * You can expand the keywords stored against an item by adding it again with extra keywords.
     * If the item is already in the database any new keywords will be mapped to it.
     *
     * @param item     Item to return for search results
     * @param keywords Arbitrary list of keywords separated by space, comma, special characters, freeform text...
     * @return True if the item was added, false if no keywords to map against the item were found (therefore item was not added)
     */
    public synchronized boolean addItem(@Nullable T item, @Nullable String keywords) {
        return addItemImpl(item, prepareKeywords(keywords, true));
    }

    /**
     * Remove previously added item. Calling this method removes the item and its mapping of keywords from the database.
     *
     * @param item Item to remove
     * @return True if the item was removed, false if no such item was found
     */
    public synchronized boolean removeItem(@Nullable T item) {
        return item != null && removeItemImpl(item);
    }

    /**
     * Find top matching item for the supplied search string
     *
     * @param searchString Raw search string
     * @return Optional containing (or not) the top scoring item
     */
    @NotNull
    public synchronized Optional<T> findItem(@Nullable String searchString) {
        return findItems(searchString, 1).stream().findFirst();
    }

    /**
     * Find top n items matching the supplied search string. Supplied string will be processed by
     * {@link KeywordsExtractor} and {@link KeywordNormalizer} before used for search, and any
     * extracted search keywords shorten than the specified minimum keyword length will be ignored.
     *
     * @param searchString     Raw search string, e.g. "new york pizza"
     * @param numberOfTopItems Number of items the returned result should be limited to
     * @return List of 0 to numberOfTopItems elements
     */
    @NotNull
    public synchronized List<T> findItems(@Nullable String searchString, int numberOfTopItems) {
        return findItemsImpl(prepareKeywords(searchString, false), numberOfTopItems)
                .stream()
                .map(ItemAndScoreWrapper::getItem)
                .collect(Collectors.toList());
    }

    /**
     * Find top matching item for the supplied search string and return it
     * wrapped in the augumented response object.
     *
     * @param searchString Raw search string
     * @return Response Response containing search keywords and possibly a single item.
     */
    @NotNull
    public synchronized Response<T> findAugumentedItem(@Nullable String searchString) {
        return findAugumentedItems(searchString, 1);
    }

    /**
     * Request an augumented result containing the search string, scores for all items
     * and list of keywords matched (can be used to provide hints to user).
     *
     * @param searchString     Raw search string, e.g. "new york pizza"
     * @param numberOfTopItems Number of items the result should be limited to
     * @return Response object containing 0 to n top scoring items and corresponding metadata
     */
    @NotNull
    public synchronized Response<T> findAugumentedItems(@Nullable String searchString, int numberOfTopItems) {
        if (searchString == null) {
            searchString = "";
        }

        Set<String> searchKeywords = prepareKeywords(searchString, false);

        List<Response.Item<T>> results = findItemsImpl(prepareKeywords(searchString, false), numberOfTopItems)
                .stream()
                .map(i -> new Response.Item<>(i.getItem(), i.getItemKeywords(), i.getScore()))
                .collect(Collectors.toList());

        return new Response<>(searchString, searchKeywords, results);
    }

    /**
     * Clear the search database.
     */
    public synchronized void clear() {
        keywordsToItemsMap.clear();
        substringsToKeywordsMap.clear();
        itemKeywordsMap.clear();
    }

    /**
     * Returns human-readable statistics string of current in-memory arrays.
     *
     * @return example output: "10 items; 100 keywords; 10000 fragments"
     */
    @NotNull
    public synchronized String getStats() {
        return String.format("%d items; %d keywords; %d fragments",
                itemKeywordsMap.size(),
                keywordsToItemsMap.size(),
                substringsToKeywordsMap.size()
        );
    }

    /*
     * Implementation methods
     */

    @NotNull
    private List<ItemAndScoreWrapper> findItemsImpl(@NotNull Set<String> searchKeywords, int maxItemsToList) {
        // empty list if no matches found
        if (searchKeywords.isEmpty() || maxItemsToList < 1)
            return Collections.emptyList(); //No viable keywords found

        // search itself
        Map<T, ItemAndScoreWrapper> unsortedResults = findAndScoreImpl(searchKeywords);

        /*
         * Choose best results sorting approach based on how large a portion
         * of matches is to be delivered to client.
         *
         * Although less efficient on smaller sample sizes sorting the result set
         * manually brings circa 50-70% better throughput for queries that result in lot of hits
         * as we can avoid sorting (discarding early) a significant proportion of the results.
         *
         * Yay for not doing unnecessary work!
         */
        if (unsortedResults.size() > maxItemsToList * 2) {
            return resultsSortManual(unsortedResults, maxItemsToList);
        } else {
            return resultsSortAPI(unsortedResults, maxItemsToList);
        }
    }

    @NotNull
    private List<ItemAndScoreWrapper> resultsSortManual(@NotNull Map<T, ItemAndScoreWrapper> unsortedResults, int maxItemsToList) {
        LinkedList<ItemAndScoreWrapper> topResults = new LinkedList<>();

        for (Map.Entry<T, ItemAndScoreWrapper> entry : unsortedResults.entrySet()) {
            ItemAndScoreWrapper score = entry.getValue();

            if (topResults.size() < maxItemsToList) {
                insertSorted(topResults, score);
            } else if (score.getScore() > topResults.getLast().getScore()) {
                insertSorted(topResults, score);
                topResults.removeLast();
            }
        }

        return topResults;
    }

    private void insertSorted(@NotNull LinkedList<ItemAndScoreWrapper> lList, @NotNull ItemAndScoreWrapper itemAndScoreWrapper) {
        for (int i = 0; i < lList.size(); i++) {
            if (itemAndScoreWrapper.getScore() > lList.get(i).getScore()) {
                lList.add(i, itemAndScoreWrapper);
                return;
            }
        }
        lList.addLast(itemAndScoreWrapper);
    }

    @NotNull
    private List<ItemAndScoreWrapper> resultsSortAPI(@NotNull Map<T, ItemAndScoreWrapper> unsortedResults, int maxItemsToList) {
        return unsortedResults.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Collections.reverseOrder()))
                .limit(maxItemsToList)
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    @NotNull
    private Map<T, ItemAndScoreWrapper> findAndScoreImpl(@NotNull Set<String> suppliedFragments) {
        // temp array to contain found matching items
        Map<T, ItemAndScoreWrapper> matchingItems = null;

        /*
         * Scoring happens here. Basic implementation that weights the
         * length of search term against matching keyword length and adds
         * a small bonus if the found keyword begins with the search term.
         */
        for (String suppliedFragment : suppliedFragments) {
            Map<T, ItemAndScoreWrapper> fragmentItems = matchSingleFragment(suppliedFragment);

            if (matchingItems == null) {
                matchingItems = fragmentItems;
            } else {
                matchingItems = matchingItems.entrySet().stream()
                        .filter(e -> fragmentItems.containsKey(e.getKey()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            }
        }

        return matchingItems != null ? matchingItems : Collections.emptyMap();
    }

    @NotNull
    private Map<T, ItemAndScoreWrapper> matchSingleFragment(@NotNull String candidateFragment) {
        Set<String> candidateKeywords = substringsToKeywordsMap.get(candidateFragment);
        if (candidateKeywords == null) {
            /*
             * Being smart here, if we have a supplied keyword we don't have a match
             * for, immediately try to shorten it by a char to see if that yields a result.
             *
             * As a result we should be able to match 'termite' against 'terminator'
             * after two backtracking iterations.
             */
            if (candidateFragment.length() > 1) {
                return matchSingleFragment(
                        candidateFragment.substring(0, candidateFragment.length() - 1)
                );
            } else {
                return Collections.emptyMap();
            }
        } else {
            /*
             * Otherwise proceed with normal 1:1 matching.
             */
            return scoreSingleFragment(candidateFragment, candidateKeywords);
        }
    }

    @NotNull
    private Map<T, ItemAndScoreWrapper> scoreSingleFragment(@NotNull String candidateFragment, @NotNull Set<String> candidateKeywords) {
        Map<T, ItemAndScoreWrapper> itemsForKeywords = new HashMap<>();

        for (String keyword : candidateKeywords) {
            double score = keywordMatchScorer.score(candidateFragment, keyword);
            Set<T> items = keywordsToItemsMap.get(keyword);

            for (T i : items) {
                ItemAndScoreWrapper w = itemsForKeywords.get(i);
                if (w == null) {
                    itemsForKeywords.put(i, new ItemAndScoreWrapper(i, itemKeywordsMap.get(i), score));
                } else {
                    w.incrementScoreBy(score);
                }
            }
        }

        return itemsForKeywords;
    }

    private boolean addItemImpl(@Nullable T item, @NotNull Set<String> suppliedKeywords) {
        if (item == null || suppliedKeywords.size() == 0) {
            return false; // No valid item or keywords found, skip adding
        }

        // Populate search maps
        for (String keyword : suppliedKeywords) {
            addItemToKeywordItemsList(item, keyword);
            mapKeywordSubstrings(keyword);
        }

        // Keep track of all the various keywords item has been assigned with (needed for item removal)
        Set<String> knownKeywords = itemKeywordsMap.get(item);

        if (knownKeywords == null) {
            knownKeywords = new HashSet<>();
            itemKeywordsMap.put(item, knownKeywords);
        }

        // Add keywords (or add keywords not already known if item already exists)
        suppliedKeywords.forEach(knownKeywords::add);

        return true;
    }

    private boolean removeItemImpl(@NotNull T item) {
        if (!itemKeywordsMap.containsKey(item))
            return false;

        //  all known keywords for the item
        Set<String> knownKeywords = itemKeywordsMap.get(item);

        // remove search term mappings
        for (String keyword : knownKeywords) {
            unmapKeywordSubstrings(keyword);
            removeItemFromKeywordItemsList(item, keyword);
        }

        // forget about the item
        itemKeywordsMap.remove(item);
        return true;
    }

    private void mapKeywordSubstrings(@NotNull String keyword) {
        for (int i = 0; i < keyword.length(); i++) {
            for (int y = i + 1; y <= keyword.length(); y++) {
                mapSingleKeywordSubstring(keyword, keyword.substring(i, y));
            }
        }
    }

    private void unmapKeywordSubstrings(@NotNull String keyword) {
        for (int i = 0; i < keyword.length(); i++) {
            for (int y = i + 1; y <= keyword.length(); y++) {
                unmapSingleKeywordSubstring(keyword, keyword.substring(i, y));
            }
        }
    }

    private void mapSingleKeywordSubstring(@NotNull String keyword, @NotNull String keywordSubstring) {
        Set<String> substringKeywordsList = substringsToKeywordsMap.get(keywordSubstring);

        if (substringKeywordsList == null) {
            substringKeywordsList = new LinkedHashSet<>();
            substringsToKeywordsMap.put(keywordSubstring, substringKeywordsList);
        }

        substringKeywordsList.add(keyword);
    }

    private void unmapSingleKeywordSubstring(@NotNull String keyword, @NotNull String keywordSubstring) {
        Set<String> substringKeywordsList = substringsToKeywordsMap.get(keywordSubstring);

        if (substringKeywordsList != null) {
            substringKeywordsList.remove(keyword);

            if (substringKeywordsList.size() == 0) {
                substringsToKeywordsMap.remove(keywordSubstring);
            }
        }
    }

    private void addItemToKeywordItemsList(@NotNull T item, @NotNull String keyword) {
        Set<T> keywordItems = keywordsToItemsMap.get(keyword);

        if (keywordItems == null) {
            keywordItems = new HashSet<>();
            keywordsToItemsMap.put(keyword, keywordItems);
        }

        keywordItems.add(item);
    }

    private void removeItemFromKeywordItemsList(@NotNull T item, @NotNull String keyword) {
        Set<T> keywordItems = keywordsToItemsMap.get(keyword);

        keywordItems.remove(item);

        if (keywordItems.size() == 0) {
            keywordsToItemsMap.remove(keyword);
        }
    }

    @NotNull
    private Set<String> prepareKeywords(@Nullable String rawInput, boolean filterShortKeywords) {
        if (rawInput == null) {
            return Collections.emptySet();
        } else {
            return prepareKeywordsList(keywordsExtractor.extract(rawInput),
                    filterShortKeywords,
                    keywordNormalizer,
                    minimumKeywordLength);
        }
    }

    @NotNull
    private Set<String> prepareKeywordsList(@NotNull Collection<String> keywords,
                                            boolean filterShorts,
                                            @NotNull KeywordNormalizer kwn,
                                            int minimumKeywordLength) {
        return keywords.stream()
                .filter(kw -> !kw.isEmpty())
                .map(kwn::normalize)
                .map(String::trim)
                .filter(s -> !filterShorts || s.length() >= minimumKeywordLength)
                .collect(Collectors.toSet());
    }
}
