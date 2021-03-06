package gui.actionmanager;

import com.amazonaws.mturk.requester.HIT;
import csv.CSVParser;
import gui.ExperimentActions;
import gui.SurveyMan;
import gui.display.Experiment;
import qc.QC;
import survey.Survey;
import survey.SurveyException;
import system.mturk.*;
import utils.Slurpie;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.util.List;
import java.util.Properties;

public class StatusAction implements MenuListener{
    private ExperimentActions action;
    private JMenu menu;

    public StatusAction (ExperimentActions action, JMenu menu) {
        this.action = action;
        this.menu = menu;
    }

    public void menuDeselected(MenuEvent event){

    }

    public void menuCanceled(MenuEvent event){

    }

    @Override
    public void menuSelected(MenuEvent actionEvent) {
        switch (action) {
            case CANCEL_RUNNING:
                list_running();
                add_cancellation();
                break;
            case STOP_SAVE:
                list_running();
                add_stop_save();
                break;
            case RUN_UNFINISHED:
                list_unfinished();
                add_run_unfinished();
                break;
            case RERUN:
                list_completed();
                add_run_completed();
                break;
            case STATUS:
                list_running();
                add_list_status();
                break;
        }
    }

    private void add_run_completed() {

    }

    private void list_completed(){

    }

    private void add_run_unfinished(){
        for (Component item : menu.getMenuComponents()) {
            final JMenuItem menuItem = (JMenuItem) item;
            menuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {
                    // load in parameters and run
                    Properties params = new Properties();
                    try {
                        params.load(new FileReader(menuItem.getName()));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    MturkLibrary.props = params;
                    SurveyPoster.updateProperties();
                    final Survey survey;
                    try {
                        survey = CSVParser.parse(params.getProperty("filename")
                                , params.getProperty("fieldsep"));
                        Thread runner = (new ExperimentAction(null)).makeRunner(survey);
                        Thread notifier = (new ExperimentAction(null)).makeNotifier(runner, survey);
                        runner.start();
                        notifier.start();
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (SurveyException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    private void list_unfinished(){
        menu.removeAll();
        // read in names of unfinished jobs
        try {
            for (String line : Slurpie.slurp(SurveyMan.UNFINISHED_JOB_FILE).split("\n")){
                String[] contents = line.split(",");
                String jobInfo = contents[0];
                JMenuItem unfinishedJob = new JMenuItem();
                unfinishedJob.setName(jobInfo);
                String[] info = jobInfo.split("_");
                unfinishedJob.setText(String.format("%s (%s) - %s", info[0], info[1], info[2]));
            }
        } catch (IOException e) {
            SurveyMan.LOGGER.info(e.getMessage());
        }
    }

    private void add_list_status(){
        for (Component item : menu.getMenuComponents()) {
            final JMenuItem menuItem = (JMenuItem) item;
            menuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {
                    String sid = menuItem.getName();
                    Survey survey = StatusAction.getFromThreadMapBySID(sid);
                    Record record = null;
                    try {
                        record = ResponseManager.getRecord(survey);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    int totalPosted = record.getAllHITs().length;
                    int responsesSoFar = record.responses.size();
                    int stillLive = ResponseManager.listAvailableHITsForRecord(record).size();
                    boolean complete = QC.complete(record.responses, record.parameters);
                    String hitId = record.getLastHIT().getHITId();
                    Experiment.updateStatusLabel(String.format("Status of survey %s with id %s:" +
                            "\n\tTotal surveys posted: %d" +
                            "\n\t#/responses so far: %d" +
                            "\n\t#/surveys still live: %d" +
                            "\n\tlast HIT posted: %s" +
                            "\n\tsurvey complete: %b"
                            , survey.sourceName
                            , survey.sid
                            , totalPosted
                            , responsesSoFar
                            , stillLive
                            , hitId
                            , complete
                    ));
                }
            });

        }
    }

    private static Survey getFromThreadMapBySID(String sid){
        for (Survey s : ExperimentAction.threadMap.keySet()){
            if (s.sid.equals(sid))
                return s;
        }
        throw new RuntimeException("Survey not found in current thread map");
    }

    private void dump(String filename, String s) throws IOException{
        BufferedWriter writer = new BufferedWriter(new FileWriter(filename, true));
        writer.write(s);
        writer.close();
    }

    private void add_stop_save(){
        // add action listener to the contents of the menu that stops all threads
        // and saves metadata for future runs
        for (Component item : menu.getMenuComponents()) {
            final JMenuItem menuItem = (JMenuItem) item;
            menuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {
                    String sid = menuItem.getName();
                    Survey survey = getFromThreadMapBySID(sid);
                    // stop threads
                    for (Thread t : (List<Thread>) ExperimentAction.threadMap.get(survey))
                        try {
                            t.join();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    // write all responses to file
                    String jobID = survey.sourceName+"_"+survey.sid+"_"+System.currentTimeMillis();
                    Record record = null;
                    try {
                        record = ResponseManager.getRecord(survey);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    // save parameters
                    try {
                        record.parameters.store(new FileWriter(jobID), "");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    // write the id and a list of the associated hits
                    String data = jobID;
                    for (HIT hit : record.getAllHITs())
                        data = data + "," + hit.getHITId();
                    data = data + "\n";
                    try {
                        dump(SurveyMan.UNFINISHED_JOB_FILE, data);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    private void add_cancellation(){
        // for each experiment listed in the menu, add an action listener that stops the thread
        // and expires all related hits
        for (Component item : menu.getMenuComponents()){
            final JMenuItem menuItem = (JMenuItem) item;
            menuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {
                    String sid = menuItem.getName();
                    Survey survey = getFromThreadMapBySID(sid);
                    Experiment.updateStatusLabel(String.format("Survey %s (%s) being cancelled..."
                            , survey.sourceName
                            , survey.sid
                    ));
                    synchronized (ExperimentAction.threadMap) {
                        List<Thread> threadList = (List<Thread>) ExperimentAction.threadMap.get(survey);
                        for (Thread t : threadList) {
                            System.out.println(t.getName() + t.getState().name());
                            Runner.interrupt = true;
                        }
                    }
                    try {
                        System.out.println("stuff");
                        Record record = ResponseManager.getRecord(survey);
                        for (HIT hit : record.getAllHITs()) {
                            System.out.println(hit.getHITId());
                            ResponseManager.expireHIT(hit);
                            Experiment.updateStatusLabel("Expired HIT : " + hit.getHITId());
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    private void list_running() {
        // clear current menu
        menu.removeAll();
        // adds the currently running experiments to the menu
        for (Survey survey : ExperimentAction.threadMap.keySet()){
            JMenuItem experiment = new JMenuItem();
            experiment.setText(String.format("%s (%s)", survey.sourceName, survey.sid));
            experiment.setName(survey.sid);
            menu.add(experiment);
        }
    }

}
