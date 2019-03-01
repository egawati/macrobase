package alexp.macrobase.pipeline;

import alexp.macrobase.ingest.StreamingDataFrameLoader;
import alexp.macrobase.ingest.Uri;
import alexp.macrobase.utils.ConfigUtils;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Iterables;
import edu.stanford.futuredata.macrobase.analysis.classify.Classifier;
import edu.stanford.futuredata.macrobase.analysis.summary.Explanation;
import edu.stanford.futuredata.macrobase.datamodel.DataFrame;
import edu.stanford.futuredata.macrobase.datamodel.Schema;
import edu.stanford.futuredata.macrobase.operator.Operator;
import edu.stanford.futuredata.macrobase.pipeline.PipelineConfig;
import edu.stanford.futuredata.macrobase.util.MacroBaseException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StreamingPipeline extends Pipeline {
    private final PipelineConfig conf;

    private final Uri inputURI;

    private String[] metricColumns;

    private boolean isStrPredicate;

    private List<String> attributes;

    private List<Classifier> classifiersChain;

    private String timeColumn;
    private String idColumn;

    public StreamingPipeline(PipelineConfig conf) throws MacroBaseException {
        this.conf = conf;

        inputURI = new Uri(conf.get("inputURI"));

        List<PipelineConfig> classifierConfigs = ConfigUtils.getObjectsList(conf, "classifiers");

        metricColumns = ConfigUtils.getAllValues(classifierConfigs, "metricColumns").toArray(new String[0]);

//        if (classifierType.equals("predicate")) {
//            Object rawCutoff = conf.get("cutoff");
//            isStrPredicate = rawCutoff instanceof String;
//        }

        attributes = conf.get("attributes");

        idColumn = conf.get("idColumn");
        timeColumn = conf.get("timeColumn");

        ConfigUtils.addToAllConfigs(classifierConfigs, "timeColumn", timeColumn);

        classifiersChain = Pipelines.getClassifiersChain(classifierConfigs);
    }

    public void run(Consumer<Explanation> resultCallback) throws Exception {
        StreamingDataFrameLoader dataLoader = getDataLoader();

        Operator<DataFrame, ? extends Explanation> summarizer = Pipelines.getSummarizer(conf, Iterables.getLast(classifiersChain).getOutputColumnName(), attributes);

        AtomicLong totalClassifierMs = new AtomicLong();
        AtomicLong totalExplanationMs = new AtomicLong();

        AtomicLong batchIndex = new AtomicLong();

        dataLoader.load(dataFrame -> {
            batchIndex.incrementAndGet();

            createAutoGeneratedColumns(dataFrame, timeColumn);

            Stopwatch sw = Stopwatch.createStarted();

            Classifier classifier = Pipelines.processChained(dataFrame, classifiersChain);
            DataFrame df = classifier.getResults();

            final long classifierMs = sw.elapsed(TimeUnit.MILLISECONDS);
            totalClassifierMs.addAndGet(classifierMs);

            saveOutliers("outliers" + batchIndex.get(), df, classifier.getOutputColumnName());
            sw = Stopwatch.createStarted();

            summarizer.process(df);
            Explanation explanation = summarizer.getResults();

            final long explanationMs = sw.elapsed(TimeUnit.MILLISECONDS);
            totalExplanationMs.addAndGet(explanationMs);

            out.printf("Classification time: %d ms (total %d ms)\nSummarization time: %d ms (total %d ms)\n\n",
                    classifierMs, totalClassifierMs.get(), explanationMs, totalExplanationMs.get());

            resultCallback.accept(summarizer.getResults());

            saveExplanation("explanation" + batchIndex.get(), df, classifier.getOutputColumnName(), explanation);
        });
    }

    private StreamingDataFrameLoader getDataLoader() throws Exception {
        Map<String, Schema.ColType> colTypes = new HashMap<>();
        Schema.ColType metricType = isStrPredicate ? Schema.ColType.STRING : Schema.ColType.DOUBLE;
        for (String column : metricColumns) {
            colTypes.put(column, metricType);
        }

        List<String> requiredColumns = Stream.concat(attributes.stream(), colTypes.keySet().stream()).collect(Collectors.toList());
        requiredColumns.add(idColumn);

        return Pipelines.getStreamingDataLoader(inputURI, colTypes, requiredColumns, conf);
    }
}
