package at.embsys.sat.jlink;

import at.embsys.sat.websocket.WebSocketConnectionHandler;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.controlsfx.control.action.Action;
import org.controlsfx.dialog.Dialog;
import org.controlsfx.dialog.Dialogs;

import java.io.*;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class OnChipDebugSystemSoftwareJLink implements Runnable {
    private final String OS = System.getProperty("os.name").toLowerCase();
    private static Process procOCDSJlink = null;
    private final TextArea debugConsole;
    private String processInput;
    private final Circle jlinkState;
    private boolean end = false;
    private static Thread redirectToJlinkThread;
    private static RedirectToJLink redirecttojlink;
    private boolean rebootRequired = false;
    private final Label jlinkPath;
    private final ComboBox comboBoxHWList;
    private static int jLinkPort = 2331;
    private static List<String> deviceInfo = new ArrayList<String>();

    public void setEnd(boolean end) {
        this.end = end;
    }

    public static int getjLinkPort() {
        return jLinkPort;
    }

    /* Konstruktor to pass the text area for displaying OOCD output */
    public OnChipDebugSystemSoftwareJLink(TextArea cmdLine, Circle jlink, Label jlkpath, ComboBox comboBoxHW, int jlinkport, List<String> deviceinfo) {

        debugConsole = cmdLine;
        jlinkState = jlink;
        jlinkPath = jlkpath;
        comboBoxHWList = comboBoxHW;
        if(jlinkport != 0)jLinkPort = jlinkport;
        deviceInfo = deviceinfo;
    }

    @Override
    public void run() {

        while (!end) {
            try {
                System.out.println("Start JLink!");

                /* Check OS */
                if ((OS.contains("linux") || OS.contains("mac"))) {
                    if(deviceInfo.size() > 1 && !deviceInfo.get(1).equals("default")) procOCDSJlink = Runtime.getRuntime().exec(jlinkPath.getText() + " -select usb="+deviceInfo.get(1)+" -Device XMC4500-1024 -if SWD"+" -port "+jLinkPort);
                    else procOCDSJlink = Runtime.getRuntime().exec(jlinkPath.getText() + " -select usb="+comboBoxHWList.getSelectionModel().getSelectedItem()+" -Device XMC4500-1024 -if SWD"+" -port "+jLinkPort);
                } else if (OS.contains("windows")) {
                    //procOCDSJlink = Runtime.getRuntime().exec(jlinkPath.getText() + " -Device XMC4500-1024 -if SWD");
                    //procOCDSeStick2 = Runtime.getRuntime().exec( "C:\\eStick2\\openocd\\bin\\openocd.exe -f scripts\\board\\estick2.cfg" );
                    if(deviceInfo.size() > 1 && !deviceInfo.get(1).equals("default")) procOCDSJlink = Runtime.getRuntime().exec(jlinkPath.getText() + " -select usb="+deviceInfo.get(1)+" -Device XMC4500-1024 -if SWD"+" -port "+jLinkPort);
                    else procOCDSJlink = Runtime.getRuntime().exec(jlinkPath.getText() + " -select usb="+comboBoxHWList.getSelectionModel().getSelectedItem()+" -Device XMC4500-1024 -if SWD"+" -port "+jLinkPort);
                } else {
                    System.out.println("Operating System not supported!");
                }




                /* Create Buffered reader for OCDS process */
                BufferedReader stdInputJLink = new BufferedReader(new
                        InputStreamReader(procOCDSJlink.getInputStream()));

                BufferedReader stdErrorJLink = new BufferedReader(new
                        InputStreamReader(procOCDSJlink.getErrorStream()));

                 /* Create log directory */
                 /* Create log directory */
                File logFile = new File(System.getProperty("user.home") + "/.debugcontrolservice");
                if(!logFile.exists())logFile.mkdirs();

                OutputStream logOOCDStream = new FileOutputStream(System.getProperty("user.home") + "/.debugcontrolservice/jlink.log");
                Calendar cal;

                System.out.println("JLink UP!");
                /* read the output from the command */
                while ((processInput = stdInputJLink.readLine()) != null) {
                    cal = Calendar.getInstance ();
                    logOOCDStream.write((cal.get(Calendar.DAY_OF_MONTH) +
                            "." + (cal.get(Calendar.MONTH) + 1) +
                            "." + cal.get(Calendar.YEAR) + "-" + cal.get(Calendar.HOUR_OF_DAY) + ":" +
                            cal.get(Calendar.MINUTE) + ":" +
                            cal.get(Calendar.SECOND) + " "+processInput + "\n").getBytes());
                    if (processInput.contains("Connected to target")) {

                          /* Start the TCP/IP JLink connection Thread*/
                        redirecttojlink = new RedirectToJLink(jlinkState);
                        redirectToJlinkThread = new Thread(redirecttojlink);
                        redirectToJlinkThread.start();

                    }

                    if (processInput.contains("WARNING: Failed to read memory")) {
                        rebootRequired = true;
                        WebSocketConnectionHandler.ws_sendMsg("reset-gdb");
                    }

                    if (processInput.contains("GDB closed TCP/IP connection") && rebootRequired) {
                        System.out.println("Destroy and restart JLink! ");
                        ///Thread.sleep(3000);
                        procOCDSJlink.destroy();
                        WebSocketConnectionHandler.ws_sendMsg("restart-required");
                    }
                    if (processInput.contains("Connected to") && !processInput.contains("Connected to target") && rebootRequired) {
                        if (WebSocketConnectionHandler.getModeOfOperation().equals("flash"))
                            WebSocketConnectionHandler.ws_sendMsg("flash");
                        if (WebSocketConnectionHandler.getModeOfOperation().equals("debug"))
                            WebSocketConnectionHandler.ws_sendMsg("debug");
                        rebootRequired = false;
                    }
                        /* UI updaten */
                    Platform.runLater(new Runnable() {
                        @Override
                        public void run() {
                            /* entsprechende UI Komponente updaten */
                            debugConsole.appendText("Output: " + processInput + "\n");
                            System.out.println(processInput);

                        }
                    });


                }
               /* UI updaten */
                Platform.runLater(new Runnable() {
                    @Override
                    public void run() {
                            /* entsprechende UI Komponente updaten */
                        jlinkState.setFill(Color.RED);
                    }
                });
            /* read any errors from the attempted command */
                System.out.println("Here is the standard error of the command (if any):\n");
                while ((processInput = stdErrorJLink.readLine()) != null) {
                    //System.out.println("Blaaa: " + processInput + "XX");

                /* UI updaten */
                    Platform.runLater(new Runnable() {
                        @Override
                        public void run() {
                        /* entsprechende UI Komponente updaten */
                            if (processInput != null) debugConsole.appendText("Errors: " + processInput + "\n");
                        }
                    });


                }
                ///Thread.sleep(3000);
            } catch (IOException e) {
                e.printStackTrace();

                if (e.toString().contains("Cannot run program")) {
                    System.out.println("IOException: " + e.toString());

                    if (!jlinkPath.getTextFill().equals(Color.RED)) {


                        Platform.runLater(new Runnable() {
                            @Override
                            public void run() {
                                jlinkPath.setTextFill(Color.RED);
                                Action response = Dialogs.create()
                                        .owner(jlinkPath.getScene().getRoot())
                                        .title("No JLink Executable")
                                        .message("The path to the JLink executable is wrong! Please specify the right path to JLink GDB server.")
                                        .actions(Dialog.Actions.CLOSE)
                                        .showError();

                            }
                        });
                    }


                }
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

    }

    public static Process getOCDSProcess() {
        return procOCDSJlink;
    }

    public static void restartJLinkRedirectService() {

        try {
            if(redirecttojlink != null)redirecttojlink.getOutToJLinkGDB().close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        redirectToJlinkThread = new Thread(redirecttojlink);
        redirectToJlinkThread.start();

    }


}