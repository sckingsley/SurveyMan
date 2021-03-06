package system.mturk;

import com.amazonaws.mturk.requester.HIT;
import survey.Survey;
import survey.SurveyResponse;
import system.Library;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Record is the class used to hold instance information about a currently running survey.
 */
public class Record {

    final public Survey survey;
    final public Properties parameters;
    final public String outputFileName;
    public List<SurveyResponse> responses;
    private Deque<HIT> hits;

    public Record(final Survey survey, final Properties parameters) throws IOException {
        File outfile = new File(String.format("%s%s%s_%s_%s.csv"
                , MturkLibrary.OUTDIR
                , MturkLibrary.fileSep
                , survey.sourceName
                , survey.sid
                , Library.TIME));
        outfile.createNewFile();
        this.outputFileName = outfile.getCanonicalPath();
        this.survey = survey;
        this.responses = new ArrayList<SurveyResponse>();
        this.parameters = parameters;
        this.hits = new ArrayDeque<HIT>();
    }

    public void addNewHIT(HIT hit) {
        hits.push(hit);
    }

    public HIT getLastHIT(){
        return hits.peekFirst();
    }

    public HIT[] getAllHITs() {
        return this.hits.toArray(new HIT[hits.size()]);
    }

    public List<String> getAllHITIds() {
        List<String> retval = new ArrayList<String>();
        for (HIT hit : this.hits){
            retval.add(hit.getHITId());
        }
        return retval;
    }

    public synchronized system.mturk.Record copy() throws IOException {
        Record r = new Record(this.survey, this.parameters);
        // don't expect responses to be removed or otherwise modified, so it's okay to just copy them over
        for (SurveyResponse sr : responses)
            r.responses.add(sr);
        // don't expect HITs to be removed either
        // double check to make sure this is being added in the proper direction
        r.hits.addAll(this.hits);
        return r;
    }
}

