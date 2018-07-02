package org.batfish.question;
// Vasu: When we wanted to migrate code to extension-pack
// package com.intentionet.ext_questions.outliersanalyzer;

import static com.google.common.base.MoreObjects.firstNonNull;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.service.AutoService;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.batfish.common.Answerer;
import org.batfish.common.BatfishException;
import org.batfish.common.plugin.IBatfish;
import org.batfish.common.plugin.Plugin;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.datamodel.answers.Schema;
import org.batfish.datamodel.collections.NamedStructureEquivalenceSet;
import org.batfish.datamodel.collections.NamedStructureEquivalenceSets;
import org.batfish.datamodel.collections.NamedStructureOutlierSet;
import org.batfish.datamodel.collections.OutlierSet;
import org.batfish.datamodel.pojo.Node;
import org.batfish.datamodel.questions.DisplayHints;
import org.batfish.datamodel.questions.HypothesisPropertySpecifier;
import org.batfish.datamodel.questions.INodeRegexQuestion;
import org.batfish.datamodel.questions.NodesSpecifier;
import org.batfish.datamodel.questions.PropertySpecifier;
import org.batfish.datamodel.questions.Question;
import org.batfish.datamodel.table.ColumnMetadata;
import org.batfish.datamodel.table.Row;
import org.batfish.datamodel.table.Row.RowBuilder;
import org.batfish.datamodel.table.TableAnswerElement;
import org.batfish.datamodel.table.TableMetadata;
import org.batfish.role.OutliersHypothesis;

// import java.util.regex.Pattern;

// import java.util.regex.Pattern;

// import java.util.regex.Pattern;

@AutoService(Plugin.class)
public class OutliersQuestionPlugin extends QuestionPlugin {

  public static final String COL_NODE = "node";

  public static class OutliersAnswerElement extends AnswerElement {

    private static final String PROP_NAMED_STRUCTURE_OUTLIERS = "namedStructureOutliers";

    private static final String PROP_SERVER_OUTLIERS = "serverOutliers";

    private SortedSet<NamedStructureOutlierSet<?>> _namedStructureOutliers;

    private SortedSet<OutlierSet<NavigableSet<String>>> _serverOutliers;

    public OutliersAnswerElement() {
      _namedStructureOutliers = new TreeSet<>();
      _serverOutliers = new TreeSet<>();
    }

    @JsonProperty(PROP_NAMED_STRUCTURE_OUTLIERS)
    public SortedSet<NamedStructureOutlierSet<?>> getNamedStructureOutliers() {
      return _namedStructureOutliers;
    }

    @JsonProperty(PROP_SERVER_OUTLIERS)
    public SortedSet<OutlierSet<NavigableSet<String>>> getServerOutliers() {
      return _serverOutliers;
    }

    @Override
    public String prettyPrint() {
      if (_namedStructureOutliers.size() == 0 && _serverOutliers.size() == 0) {
        return "";
      }

      StringBuilder sb = new StringBuilder("Results for outliers\n");

      for (OutlierSet<?> outlier : _serverOutliers) {
        sb.append("  Hypothesis: every node should have the following set of ");
        sb.append(outlier.getName() + ": " + outlier.getDefinition() + "\n");
        sb.append("  Outliers: ");
        sb.append(outlier.getOutliers() + "\n");
        sb.append("  Conformers: ");
        sb.append(outlier.getConformers() + "\n\n");
      }

      for (NamedStructureOutlierSet<?> outlier : _namedStructureOutliers) {
        switch (outlier.getHypothesis()) {
          case SAME_DEFINITION:
            sb.append(
                "  Hypothesis: every "
                    + outlier.getStructType()
                    + " named "
                    + outlier.getName()
                    + " should have the same definition\n");
            break;
          case SAME_NAME:
            sb.append("  Hypothesis:");
            if (outlier.getNamedStructure() != null) {
              sb.append(" every ");
            } else {
              sb.append(" no ");
            }
            sb.append(
                "node should define a "
                    + outlier.getStructType()
                    + " named "
                    + outlier.getName()
                    + "\n");
            break;
          default:
            throw new BatfishException("Unexpected hypothesis" + outlier.getHypothesis());
        }
        sb.append("  Outliers: ");
        sb.append(outlier.getOutliers() + "\n");
        sb.append("  Conformers: ");
        sb.append(outlier.getConformers() + "\n\n");
      }
      return sb.toString();
    }

    @JsonProperty(PROP_NAMED_STRUCTURE_OUTLIERS)
    public void setNamedStructureOutliers(
        SortedSet<NamedStructureOutlierSet<?>> namedStructureOutliers) {
      _namedStructureOutliers = namedStructureOutliers;
    }

    @JsonProperty(PROP_SERVER_OUTLIERS)
    public void setServerOutliers(SortedSet<OutlierSet<NavigableSet<String>>> serverOutliers) {
      _serverOutliers = serverOutliers;
    }
  }

  public static class OutliersAnswerer extends Answerer {

    static final String COL_ISSUE = "outliersIssue";
    private OutliersAnswerElement _answerElement;

    private Map<String, Configuration> _configurations;

    // the node names that match the question's node regex
    private Set<String> _nodes;

    // only report outliers that represent this percentage or less of
    // the total number of nodes
    private static double OUTLIERS_THRESHOLD = 0.34;

    // if this flag is true, report all outliers, even those that exceed the threshold above,
    // and including situations when there are zero outliers
    private boolean _verbose;

    public OutliersAnswerer(Question question, IBatfish batfish) {
      super(question, batfish);
    }

    private <T> void addNamedStructureOutliers(
        OutliersHypothesis hypothesis,
        NamedStructureEquivalenceSets<T> equivSet,
        SortedSet<NamedStructureOutlierSet<?>> rankedOutliers) {
      String structType = equivSet.getStructureClassName();
      for (Map.Entry<String, SortedSet<NamedStructureEquivalenceSet<T>>> entry :
          equivSet.getSameNamedStructures().entrySet()) {
        String name = entry.getKey();
        SortedSet<NamedStructureEquivalenceSet<T>> eClasses = entry.getValue();
        NamedStructureEquivalenceSet<T> max =
            eClasses
                .stream()
                .max(Comparator.comparingInt(es -> es.getNodes().size()))
                .orElseThrow(
                    () ->
                        new BatfishException(
                            "Named structure " + name + " has no equivalence classes"));
        SortedSet<String> conformers = max.getNodes();

        SortedSet<String> outliers =
            eClasses
                .stream()
                .filter(eClass -> eClass != max)
                .flatMap(eClass -> eClass.getNodes().stream())
                .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()));
        rankedOutliers.add(
            new NamedStructureOutlierSet<>(
                hypothesis, structType, name, max.getNamedStructure(), conformers, outliers));
      }
    }

    private <T> void addPropertyOutliers(
        String name,
        TableAnswerElement innerTableT,
        int colIndexT,
        SortedSet<OutlierSet<T>> rankedOutliers) {

      // partition the nodes into equivalence classes based on their values for the
      // property of interest
      Map<T, SortedSet<String>> equivSetsT = new HashMap<>();

      // break down the table into its various values and corresponding keys
      Multimap<List<Object>, List<Object>> valueToKeys = HashMultimap.create();

      innerTableT
          .getRows()
          .getData()
          .forEach(
              row -> {
                valueToKeys.put(
                    row.getValue(innerTableT.getMetadata().getColumnMetadata()),
                    row.getKey(innerTableT.getMetadata().getColumnMetadata()));
              });

      /*Traverse each row of the table to handle the data.*/
      for (Row row : innerTableT.getRows().getData()) {

        // Node Name specific to that row.
        String rowNodeName = row.get("node").get("name").toString();
        rowNodeName.replace("\"\"", "\"");

        // Row Entry specific  to column "colIndexT"
        Object rowValueDef =
            row.getValue(innerTableT.getMetadata().getColumnMetadata()).get(colIndexT - 1);

        SortedSet<String> matchingNodesTemp = equivSetsT.getOrDefault(rowValueDef, new TreeSet<>());
        matchingNodesTemp.add(rowNodeName);
        equivSetsT.put((T) rowValueDef, matchingNodesTemp);
      }

      // the equivalence class of the largest size is treated as the one whose value is
      // hypothesized to be the correct one
      Map.Entry<T, SortedSet<String>> max =
          equivSetsT
              .entrySet()
              .stream()
              .max(Comparator.comparingInt(e -> e.getValue().size()))
              .orElseThrow(
                  () -> new BatfishException("Set " + name + " has no equivalence classes"));
      SortedSet<String> conformers = max.getValue();
      T definition = max.getKey();
      equivSetsT.remove(definition);

      SortedSet<String> outliers = new TreeSet<>();
      for (SortedSet<String> nodes : equivSetsT.values()) {
        outliers.addAll(nodes);
      }
      if (_verbose || isWithinThreshold(conformers, outliers)) {
        rankedOutliers.add(new OutlierSet<T>(name, definition, conformers, outliers));
      }
    }

    @Override
    public OutliersAnswerElement answer() {

      OutliersQuestion question = (OutliersQuestion) _question;
      _answerElement = new OutliersAnswerElement();

      _configurations = _batfish.loadConfigurations();
      _nodes = question.getNodeRegex().getMatchingNodes(_batfish);
      _verbose = question.getVerbose();

      switch (question.getHypothesis()) {
        case SAME_DEFINITION:
        case SAME_NAME:
          SortedSet<NamedStructureOutlierSet<?>> outliers = namedStructureOutliers(question);
          _answerElement.setNamedStructureOutliers(outliers);
          break;
        case SAME_SERVERS:
          _answerElement.setServerOutliers(serverOutliers(question));

          break;
        default:
          throw new BatfishException(
              "Unexpected outlier detection hypothesis " + question.getHypothesis());
      }

      return _answerElement;
    }

    @VisibleForTesting
    static Multiset<Row> rawAnswer(
        OutliersQuestion question,
        Map<String, Configuration> configurations,
        Set<String> nodes,
        Map<String, ColumnMetadata> columns) {
      Multiset<Row> rows = HashMultiset.create();

      for (String nodeName : nodes) {
        RowBuilder row = Row.builder(columns).put(COL_NODE, new Node(nodeName));

        for (String property : question.getPropertySpec().getMatchingProperties()) {
          PropertySpecifier.fillProperty(
              HypothesisPropertySpecifier.JAVA_MAP.get(property),
              configurations.get(nodeName),
              property,
              row);
        }

        rows.add(row.build());
      }

      return rows;
    }

    /**
     * Creates a {@link TableMetadata} object from the question.
     *
     * @param question The question
     * @return The resulting {@link TableMetadata} object
     */
    static TableMetadata createMetadata(OutliersQuestion question) {
      List<ColumnMetadata> columnMetadata =
          new Builder<ColumnMetadata>()
              .add(new ColumnMetadata(COL_NODE, Schema.NODE, "Node", true, false))
              .addAll(
                  question
                      .getPropertySpec()
                      .getMatchingProperties()
                      .stream()
                      .map(
                          prop ->
                              new ColumnMetadata(
                                  prop,
                                  HypothesisPropertySpecifier.JAVA_MAP.get(prop).getSchema(),
                                  "Property " + prop,
                                  false,
                                  true))
                      .collect(Collectors.toList()))
              .build();

      DisplayHints dhints = question.getDisplayHints();
      if (dhints == null) {
        dhints = new DisplayHints();
        dhints.setTextDesc(String.format("Properties of node ${%s}.", COL_NODE));
      }
      return new TableMetadata(columnMetadata, dhints);
    }

    static TableMetadata createMetadata(Question question, TableAnswerElement inputTable) {
      List<ColumnMetadata> columnMetadataMap =
          new ImmutableList.Builder<ColumnMetadata>()
              .addAll(inputTable.getMetadata().getColumnMetadata())
              .add(
                  new ColumnMetadata(
                      COL_ISSUE, Schema.ISSUE, "Issue flagged by outliers analysis", false, false))
              .build();

      DisplayHints dhints = question.getDisplayHints();
      if (dhints == null) {
        dhints = new DisplayHints();
        dhints.setTextDesc("Value is an outlier.");
      }
      return new TableMetadata(columnMetadataMap, dhints);
    }

    // check that there is at least one outlier, but also that the fraction of outliers
    // does not exceed our threshold for reporting
    private static boolean isWithinThreshold(
        SortedSet<String> conformers, SortedSet<String> outliers) {
      double cSize = conformers.size();
      double oSize = outliers.size();
      return oSize > 0 && (oSize / (cSize + oSize)) <= OUTLIERS_THRESHOLD;
    }

    private SortedSet<NamedStructureOutlierSet<?>> namedStructureOutliers(
        OutliersQuestion question) {

      // first get the results of compareSameName
      CompareSameNameQuestionPlugin.CompareSameNameQuestion inner =
          new CompareSameNameQuestionPlugin.CompareSameNameQuestion(
              null,
              new TreeSet<>(),
              null,
              question.getNamedStructTypes(),
              question.getNodeRegex(),
              true);
      CompareSameNameQuestionPlugin.CompareSameNameAnswerer innerAnswerer =
          new CompareSameNameQuestionPlugin().createAnswerer(inner, _batfish);
      CompareSameNameQuestionPlugin.CompareSameNameAnswerElement innerAnswer =
          innerAnswerer.answer();

      SortedMap<String, NamedStructureEquivalenceSets<?>> equivalenceSets =
          innerAnswer.getEquivalenceSets();

      OutliersHypothesis hypothesis = question.getHypothesis();
      switch (hypothesis) {
        case SAME_DEFINITION:
          // nothing to do before ranking outliers
          break;
        case SAME_NAME:
          // create at most two equivalence classes for each name:
          // one containing the nodes that have a structure of that name,
          // and one containing the nodes that don't have a structure of that name
          for (NamedStructureEquivalenceSets<?> eSets : equivalenceSets.values()) {
            toNameOnlyEquivalenceSets(eSets, innerAnswer.getNodes());
          }
          break;
        default:
          throw new BatfishException("Default case of switch should be unreachable");
      }

      SortedSet<NamedStructureOutlierSet<?>> outliers =
          rankNamedStructureOutliers(hypothesis, equivalenceSets);

      if (!_verbose) {
        // remove outlier sets where the hypothesis is that a particular named structure
        // should *not* exist (this  happens when more nodes lack such a structure than contain
        // such a structure).  such hypotheses do not seem to be useful in general.
        outliers.removeIf(oset -> oset.getNamedStructure() == null);

        // remove outlier sets that don't meet our threshold
        outliers.removeIf(oset -> !isWithinThreshold(oset.getConformers(), oset.getOutliers()));
      }
      return outliers;
    }

    private SortedSet<OutlierSet<NavigableSet<String>>> serverOutliers(OutliersQuestion question) {

      int colIndex = 0;
      TableMetadata tableMetadata = createMetadata(question);
      TableAnswerElement innerAnswerTable = new TableAnswerElement(tableMetadata);
      Map<String, Configuration> configurations = _batfish.loadConfigurations();
      Set<String> nodes = question.getNodeRegex().getMatchingNodes(_batfish);

      Multiset<Row> propertyRows =
          rawAnswer(question, configurations, nodes, tableMetadata.toColumnMap());
      innerAnswerTable.postProcessAnswer(question, propertyRows);

      if (!(innerAnswerTable instanceof TableAnswerElement)) {
        throw new IllegalArgumentException("The inner question does not produce table answers");
      }

      // TableMetadata metadata = createMetadata(question, inneranswertable);
      SortedSet<OutlierSet<NavigableSet<String>>> rankedOutliers = new TreeSet<>();

      Iterator<Entry<String, ColumnMetadata>> iterator =
          tableMetadata.toColumnMap().entrySet().iterator();
      while (iterator.hasNext()) {
        Entry<String, ColumnMetadata> entry = iterator.next();
        String serverSet = entry.getValue().getName();
        if (!(serverSet.equalsIgnoreCase("node") || serverSet.equalsIgnoreCase("outliersIssue"))) {

          addPropertyOutliers(serverSet, innerAnswerTable, colIndex, rankedOutliers);
        }
        colIndex++;
      }
      return rankedOutliers;
    }

    /**
     * Use the results of CompareSameName to partition nodes into those containing a structure of a
     * given name and those lacking such a structure. This information will later be used to test
     * the sameName hypothesis.
     */
    private <T> void toNameOnlyEquivalenceSets(
        NamedStructureEquivalenceSets<T> eSets, Set<String> nodes) {
      ImmutableSortedMap.Builder<String, SortedSet<NamedStructureEquivalenceSet<T>>> newESetsMap =
          new ImmutableSortedMap.Builder<>(Comparator.naturalOrder());
      for (Map.Entry<String, SortedSet<NamedStructureEquivalenceSet<T>>> entry :
          eSets.getSameNamedStructures().entrySet()) {
        SortedSet<NamedStructureEquivalenceSet<T>> eSetSets = entry.getValue();
        SortedSet<String> presentNodes =
            eSetSets
                .stream()
                .flatMap(eSet -> eSet.getNodes().stream())
                .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()));
        T struct = eSetSets.first().getNamedStructure();

        SortedSet<String> absentNodes =
            ImmutableSortedSet.copyOf(Sets.difference(ImmutableSet.copyOf(nodes), presentNodes));
        NamedStructureEquivalenceSet<T> presentSet =
            new NamedStructureEquivalenceSet<T>(presentNodes.first(), struct);
        presentSet.setNodes(presentNodes);
        String name = entry.getKey();
        ImmutableSortedSet.Builder<NamedStructureEquivalenceSet<T>> eqSetsBuilder =
            new ImmutableSortedSet.Builder<>(Comparator.naturalOrder());
        eqSetsBuilder.add(presentSet);
        if (!absentNodes.isEmpty()) {
          NamedStructureEquivalenceSet<T> absentSet =
              new NamedStructureEquivalenceSet<T>(absentNodes.first(), null);
          absentSet.setNodes(absentNodes);
          eqSetsBuilder.add(absentSet);
        }
        newESetsMap.put(name, eqSetsBuilder.build());
      }
      eSets.setSameNamedStructures(newESetsMap.build());
    }

    /* a simple first approach to detect and rank outliers:
     * compute the z-score (see Engler's 2001 paper on detecting outliers) for each
     * <structure type, name> pair, based on a hypothesis that the equivalence class
     * with the largest number of elements is correct and the property equivalence classes
     * represent bugs
     */
    private SortedSet<NamedStructureOutlierSet<?>> rankNamedStructureOutliers(
        OutliersHypothesis hypothesis,
        SortedMap<String, NamedStructureEquivalenceSets<?>> equivSets) {
      SortedSet<NamedStructureOutlierSet<?>> rankedOutliers = new TreeSet<>();
      for (NamedStructureEquivalenceSets<?> entry : equivSets.values()) {
        addNamedStructureOutliers(hypothesis, entry, rankedOutliers);
      }
      return rankedOutliers;
    }
  }

  // <question_page_comment>
  /**
   * Detects and ranks outliers based on a comparison of nodes' configurations.
   *
   * <p>If many nodes have a structure of a given name and a few do not, this may indicate an error.
   * If many nodes have a structure named N whose definition is identical, and a few nodes have a
   * structure named N that is defined differently, this may indicate an error. This question
   * leverages this and similar intuition to find outliers.
   *
   * @type Outliers multifile
   * @param serverSets Set of server-set names to analyze drawn from ( DnsServers, LoggingServers,
   *     NtpServers, SnmpTrapServers, TacacsServers) Default value is '[]' (which denotes all
   *     server-set names). This option is applicable to the "sameServers" hypothesis.
   * @param namedStructTypes Set of structure types to analyze drawn from ( AsPathAccessList,
   *     AuthenticationKeyChain, CommunityList, IkeGateway, IkePolicy, IkeProposal, IpAccessList,
   *     IpsecPolicy, IpsecProposal, IpsecVpn, RouteFilterList, RoutingPolicy) Default value is '[]'
   *     (which denotes all structure types). This option is applicable to the "sameName" and
   *     "sameDefinition" hypotheses.
   * @param nodeRegex Regular expression for names of nodes to include. Default value is '.*' (all
   *     nodes).
   * @param hypothesis A string that indicates the hypothesis being used to identify outliers.
   *     "sameDefinition" indicates a hypothesis that same-named structures should have identical
   *     definitions. "sameName" indicates a hypothesis that all nodes should have structures of the
   *     same names. "sameServers" indicates a hypothesis that all nodes should have the same set of
   *     protocol-specific servers (e.g., DNS servers). Default is "sameDefinition".
   * @param verbose A boolean that indicates whether all results should be returned, including
   *     situations when a hypothesis yields no outliers and situations when a hypothesis yields a
   *     number of outliers that exceeds our threshold for considering it a likely error. Default
   *     value is false.
   */
  public static final class OutliersQuestion extends Question implements INodeRegexQuestion {

    private static final String PROP_HYPOTHESIS = "hypothesis";

    private static final String PROP_NAMED_STRUCT_TYPES = "namedStructTypes";

    private static final String PROP_NODE_REGEX = "nodeRegex";

    private static final String PROP_SERVER_SETS = "serverSets";

    private static final String PROP_VERBOSE = "verbose";

    private static final String PROP_PROPERTY_SPEC = "propertySpec";

    private OutliersHypothesis _hypothesis;

    private SortedSet<String> _namedStructTypes;

    private NodesSpecifier _nodeRegex;

    private SortedSet<String> _serverSets;

    private HypothesisPropertySpecifier _propertySpec;

    private boolean _verbose;

    // private PropertySpecifier _propertySpec;

    public OutliersQuestion(
        @JsonProperty(PROP_NODE_REGEX) NodesSpecifier nodeRegex,
        @JsonProperty(PROP_PROPERTY_SPEC) HypothesisPropertySpecifier propertySpec) {
      _namedStructTypes = new TreeSet<>();
      _serverSets = new TreeSet<>();
      _nodeRegex = NodesSpecifier.ALL;
      _hypothesis = OutliersHypothesis.SAME_DEFINITION;
      _propertySpec = firstNonNull(propertySpec, HypothesisPropertySpecifier.ALL);
    }

    @Override
    public boolean getDataPlane() {
      return false;
    }

    @JsonProperty(PROP_HYPOTHESIS)
    public OutliersHypothesis getHypothesis() {
      return _hypothesis;
    }

    @Override
    public String getName() {
      return "outliers";
    }

    @JsonProperty(PROP_NAMED_STRUCT_TYPES)
    public SortedSet<String> getNamedStructTypes() {
      return _namedStructTypes;
    }

    @Override
    @JsonProperty(PROP_NODE_REGEX)
    public NodesSpecifier getNodeRegex() {
      return _nodeRegex;
    }

    @JsonProperty(PROP_SERVER_SETS)
    public SortedSet<String> getServerSets() {
      return _serverSets;
    }

    @JsonProperty(PROP_VERBOSE)
    public boolean getVerbose() {
      return _verbose;
    }

    @JsonProperty(PROP_HYPOTHESIS)
    public void setHypothesis(OutliersHypothesis hypothesis) {
      _hypothesis = hypothesis;
    }

    @JsonProperty(PROP_NAMED_STRUCT_TYPES)
    public void setNamedStructTypes(SortedSet<String> namedStructTypes) {
      _namedStructTypes = namedStructTypes;
    }

    @Override
    @JsonProperty(PROP_NODE_REGEX)
    public void setNodeRegex(NodesSpecifier regex) {
      _nodeRegex = regex;
    }

    @JsonProperty(PROP_SERVER_SETS)
    public void setServerSets(SortedSet<String> serverSets) {
      _serverSets = serverSets;
    }

    @JsonProperty(PROP_VERBOSE)
    public void setVerbose(boolean verbose) {
      _verbose = verbose;
    }

    @JsonProperty(PROP_PROPERTY_SPEC)
    public PropertySpecifier getPropertySpec() {
      return _propertySpec;
    }
  }

  @Override
  protected OutliersAnswerer createAnswerer(Question question, IBatfish batfish) {
    return new OutliersAnswerer(question, batfish);
  }

  @Override
  protected OutliersQuestion createQuestion() {
    return new OutliersQuestion(null, null);
  }
}
