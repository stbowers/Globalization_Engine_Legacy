package com.julianEngine;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.ServiceLoader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.swing.JFrame;

import com.julianEngine.config.EngineConstants;
import com.julianEngine.core.Point;
import com.julianEngine.core.Shape;
import com.julianEngine.core.Vector;
import com.julianEngine.core.World;
import com.julianEngine.core.World.IDAlreadyInUseException;
import com.julianEngine.core.World.WorldWaitListener;
import com.julianEngine.data.DataManager;
import com.julianEngine.data.JDFMaster;
import com.julianEngine.data.JDFPlugin;
import com.julianEngine.data.PreInitializer;
import com.julianEngine.data.pluginCommunication.JDFMessageManager;
import com.julianEngine.data.pluginCommunication.JDFMessageReceiver;
import com.julianEngine.data.pluginCommunication.JDFMessageSender;
import com.julianEngine.graphics.Camera;
import com.julianEngine.graphics.Frame;
import com.julianEngine.graphics.UI.UIBitmapMask;
import com.julianEngine.graphics.UI.UIButton;
import com.julianEngine.graphics.UI.UIButton.UIButtonListener;
import com.julianEngine.graphics.UI.UIMask.UIMaskListener;
import com.julianEngine.graphics.shapes.ProgressBar;
import com.julianEngine.graphics.shapes.Rectangle;
import com.julianEngine.graphics.shapes.Sprite;
import com.julianEngine.graphics.shapes.Text;
import com.julianEngine.utility.Log;
import com.sun.xml.internal.messaging.saaj.util.ByteInputStream;

/**
 * Julian Engine v1.2 - coded in Java with default libraries. Successor to v1.0
 * which, although worked somewhat and taught valuable lessons, was ultimately
 * a massive failure. May it rest in peace.
 */
public class Engine2D extends JFrame implements WindowListener, KeyListener {
	/*--------Public Static Variables-------*/
	public static String versionID = "v1.2.5_a05"; //Engine version
	public static JDFMaster masterFile; //Variable holder for the master plugin file (see JDFMaster)
	public static ArrayList<JDFPlugin> pluginFiles; //ArrayList holder for each plugin file (see JDFPlugin)
	public static boolean debugMode = true; //Should the engine be run in debug mode (set to false for release)
	
	/*--------Private Static Variables------*/
	private static final long serialVersionUID = -7981520978541595849L; //Serial Version UID for serializing the engine for save files and the like
	private static boolean engineStarted = false; //set to true when the first instance of Engine2D is created. Prevents plugins from creating a second instance
	private static Engine2D engineReference; //static reference to the engine - only one instance can be created, and this points to it
	private static boolean showLoadBar = false; //should the detailed loading bar be shown?
	private static boolean initializerAgreement = false; //Have all the plugins agreed on an initializer? See code in main() for more details
	private static PreInitializer proposedInitializer; //Variable holder for the initializer currently being proposed (see above and main())
	
	/*--------Public Instance Variables-----*/
	public World mainWorld; //Variable holder for the main world (populated by master file - usually a title screen of some sort)
	public Camera mainCamera; //Variable holder for the camera rendering the window
	public Frame mainView = new Frame(1080, 720); //Variable holder for the frame that the camera renders to, and is displayed in the window
	
	/*--------Private Instance Variables----*/
	private BufferStrategy bufferStrategy; //buffer strategy for the frame - renders two frames in advanced to improve performance
	private boolean paused = false; //is the game paused?
	private int fpsLock = 60; //default fps lock - used in constructor to set up render loop
	private ArrayList<EngineLoadListener> loadListeners = new ArrayList<EngineLoadListener>(); //list of parties who are interested in the loading state of the game
	private boolean consoleActive = false; //is the console active (THIS SHOULD BE TAKEN UP BY THE MASTER FILE, AND EVENTUALLY REMOVED)
		//settings for render loop (fps)
	private int renders = 0; //holder for how many times the frame has been drawn since last check
	private long lastUpdateNano = System.nanoTime(); //system time at the last check
	private ScheduledExecutorService renderLoopExecutor = Executors.newSingleThreadScheduledExecutor(); //holder for the executor service that runs the render loop at regular intervals
	//Anonymous class for the render loop stored in a runnable object
	private Runnable renderLoop = () -> {
		if(!paused){
			try{
				mainCamera.renderPerspective(mainView, bufferStrategy);
				renders++;
				
				if((System.nanoTime()-lastUpdateNano)>250000000){
					long timePassed = System.nanoTime()-lastUpdateNano;
					lastUpdateNano = System.nanoTime();
					double fps = ((float)renders/(double)((double)timePassed/1000000000f));
					mainCamera.setFPS((float) fps);
					renders = 0;
				}
			}catch(Exception e){
				Log.error("Error in render loop: ");
				e.printStackTrace();
			}
		}
		
	};
	
	/*--------Code--------------------------*/
	/**Entry point for application. Creates engine, loads plugins, and then hands opperation off
	 * to master plugin file. if first arg is equal to "--testengine" no plugins will be loaded,
	 * and the engine will be tested instead.
	 * @throws NoMasterDataFileFoundException 
	 * @throws MultipleMasterFilesFoundException 
	**/
	public static void main(String[] args){
		//Rename the thread. This has two purposes: first to help find errors during debugging, and also to print in the log
		Thread.currentThread().setName("ENGINE-main");
		try{
			try {
				//Create the engine
				Engine2D engine = new Engine2D("JulianEngine "+versionID);
				
				if(args.length > 0 && args[0].equals("--testengine")){ //if the --testengine option was used, test the engine instead of loading the game
					//Test Engine
					Log.trace("Testing engine...");
					testEngine(engine);
				}else{
					//Load plugins
					Log.trace("Loading plugins...");
					
					masterFile = loadMasterFile(); //load master file
					pluginFiles = loadPluginFiles(); //load plugin files
					
					//Log if either masterFile or pluginFiles is null (null masterFile is an error)
					if(masterFile==null){
						//we should get an exception if there were any errors loading the master file (i.e. no master file, or multiple), so this should never run,
						//and if we see this error in the log before a crash, we know something is very wrong
						Log.fatal("Unknown error while loading master file (null pointer)");
						System.exit(-1); //shutdown the program with an error code
					}
					
					if(pluginFiles==null){ //if we don't have any plugin files loaded, tell the user and proceed
						Log.info("No plugin files found - proceeding without");
					}
					
					//!!!!!!!!!!!PLUGIN INITIALIZER AGREEMENT CODE!!!!!!!!!!!!!!!//
					//WARNING: IF NOT USED PROPERY, THIS CAN TURN INTO AN INFINITE LOOP (IF NO PLUGINS CAN AGREE ON AN INITIALIZER)!!!!!!!!!!
					//Inform plugins that we intend to initialize with the master plugin
					proposedInitializer = masterFile; //propose to use the master plugin file for the initializer
					Log.trace("Proposing Pre-Initializer");
					while(!initializerAgreement){ //loop until an initializer is decided on
						initializerAgreement = true; //if this isn't set to false by the end, we can assume an initializer was agreed on
						
						//boradcast our intention to use whatever initializer is being proposed
						JDFMessageManager.broadcastMessage(String.format("proposed-initializer:%s", proposedInitializer.getName()), new JDFMessageSender(){
							//anonymous class to represent the engine as a message sender
							@Override
							public String getName() {
								return "Engine2D loader";
							}
	
							@Override
							public void replyReceived(String originalMessage, byte[] reply, JDFMessageReceiver receiver) {
								//If we get a reply to our message, we need to deal with it - usually a reply means that a plugin doesn't agree on the initializer
								Log.trace(receiver.getName()+" disagrees with chosen initializer"); //tell the user what's happening
								initializerAgreement = false; //we don't agree anymore, make sure to update the boolean
								String msgReply = new String(reply); //turn the reply into a string to check the first bit
								ByteArrayInputStream replyStream = new ByteArrayInputStream(reply); //if the first bit is good, we need to have a stream for the rest of the data
								if(msgReply.startsWith("alternate-initializer:")){ //if the plugin is in fact proposing a new initializer, we need to get that initializer
									//new initializer proposed
									for(int i=0;i<"alternate-initializer:".length();i++){
										replyStream.read(); //dump bytes corresponding to the string, we only need the bits for the object
									}
									
									//the rest of the data should be a serialized PreInitializer
									try {
										ObjectInputStream initializerStream = new ObjectInputStream(replyStream); //create an object stream from the byte stream
										proposedInitializer = (PreInitializer) initializerStream.readObject(); //read the serialized object
									} catch (Exception e) {
										//If we get an exception, the byte stream was likely corrupted, or not formed properly by the plugin
										Log.error("Could not read pre-initializer object proposed by"+receiver.getName());
										e.printStackTrace();
									}
								}
							}
						});
					}
					
					
					//after leaving the loop we now have an initializer that all plugins agree on
					
					Thread.currentThread().setName("ENGINE-preInitializer"); //change the name of the thread - for debug and log
					proposedInitializer.preInit(); //use the agreed upon initializer to initialize the game
					Thread.currentThread().setName("ENGINE-main"); //set the thread name back to ENGINE-main
					
					//LOADING SCREEN CODE
					World loadingScreen = new World(-1); //create a new world for the loading screen
					Thread.currentThread().setName(masterFile.getPluginID()+"-loadScreen"); //change the thread name to signify we're getting the load screen
					loadingScreen = masterFile.createLoadingScreen(loadingScreen, engine.mainView); //ask the master file for the load screen
					Thread.currentThread().setName("ENGINE-main"); //change thread name back
					
					ProgressBar loadingBar = new ProgressBar(new Point(20, 100, 5), 300, 25); //create a progress bar for detailed loading progress
					Text loadingText = new Text(new Point(20, 120, 5), "", Color.WHITE, new Font("Ariel", Font.PLAIN, 12), engine.mainView); //text to display loading progress on
					
					//set up and stylize loading bar and loading text
					loadingBar.setBarColor(Color.WHITE);
					loadingBar.setBorderColor(Color.WHITE);
					loadingBar.centerX(engine.mainView);
					loadingText.centerX(engine.mainView);
					
					if(showLoadBar||debugMode){ //if we should show the loading bar, or if we are in debug name, add it to the loading screen
						loadingScreen.addShape(loadingBar);
						loadingScreen.addShape(loadingText);
					}
					
					Thread customLoadThread; //thread to run custom loading code in - we put this in a new thread so we can limit the time spent on it, and keep load times low
					
					engine.mainCamera.moveToWorld(loadingScreen.getID()); //put the camera in the loading screen, to render it
					World.waitForWorldToBeReady(World.getWorldForID(loadingScreen.getID())); //wait for the loading screen to be ready, so we're not showing nothing when the game opens
					engine.setVisible(true); //once the loading screen is ready, show the window
					
					//Set the custom loading thread to 10% - since the code is loaded. We don't set the loading bar here because
					//it tracks the individual progress of each task
					customLoadThread = new Thread(){ //reset the load thread to the following code
						public void run(){
							for(EngineLoadListener l:engine.loadListeners){
								l.setLoadingPercentage((float) .1); //for each party interested in engine loading percentage, set the percenatge to 10%
							}
						}
					};
					customLoadThread.start();
					customLoadThread.join(1000); //start thread, and wait a maximum of 1 second for it to finish (so custom loaders don't hold us up)
					
					
					//INITIALIZE PLUGINS
					loadingBar.setPercentFilled((float) 0); //since we're starting a new task, set the loading bar to 0 and set the text to our current task
					loadingText.setText("Initializing Plugins...");
					loadingText.centerX(engine.mainView); //re-center text since it changed
					
					Thread.currentThread().setName(masterFile.getPluginID()+"-init"); //change the thread name to show we're in init for the master file
					masterFile.init((args.length>0)?args[0]:""); //run master init first with the first argument if it exists
					if(pluginFiles!=null){ //if we have any plugins
						for(JDFPlugin plugin:pluginFiles){ //for each loaded plugin:
							Thread.currentThread().setName(plugin.getPluginID()+"-init"); //set the thread name to signal we're initing a plugin
							plugin.init((args.length>0)?args[0]:""); //init each plugin with the first argument if it exists
						}
					}
					Thread.currentThread().setName("ENGINE-main"); //when we're done go back to our thread name
					
					loadingBar.setPercentFilled((float) 1); //we're done, so set the bar to 100%
					
					//set custom loading percentage to 30%
					customLoadThread = new Thread(){
						public void run(){
							for(EngineLoadListener l:engine.loadListeners){
								l.setLoadingPercentage((float) .3);
							}
						}
					};
					customLoadThread.start();
					customLoadThread.join(1000); //again wait a max of 1 second
					
					
					//POST-INIT PLUGINS
					loadingBar.setPercentFilled((float) 0); //set loading bar
					loadingText.setText("Post-Initializing Plugins...");
					loadingText.centerX(engine.mainView);
					
					Thread.currentThread().setName(masterFile.getPluginID()+"-postInit");
					masterFile.postInit(); //run postInit for master first
					if(pluginFiles!=null){
						for(JDFPlugin plugin:pluginFiles){
							Thread.currentThread().setName(plugin.getPluginID()+"-postInit");
							plugin.postInit(); //run postInit in order
						}
					}
					Thread.currentThread().setName("ENGINE-main");
					
					loadingBar.setPercentFilled((float) 1);
					
					customLoadThread = new Thread(){
						public void run(){
							for(EngineLoadListener l:engine.loadListeners){
								l.setLoadingPercentage((float) .6);
							}
						}
					};
					customLoadThread.start();
					customLoadThread.join(1000);
					
					engine.mainCamera.forceRender();
					
					loadingBar.setPercentFilled((float) 0);
					loadingText.setText("Creating Title Screen...");
					loadingText.centerX(engine.mainView);
					
					engine.mainCamera.forceRender();
					
					Log.trace("about to make title screen");
					Thread.currentThread().setName(masterFile.getPluginID()+"-createMainScreen");
					engine.mainWorld = masterFile.createMainScreen(engine.mainWorld, engine.mainView, (args.length>0)?args[0]:"");
					Thread.currentThread().setName("ENGINE-main");
					Log.trace("made title screen");
					
					loadingBar.setPercentFilled((float) 1);
					
					engine.mainCamera.forceRender();
					
					customLoadThread = new Thread(){
						public void run(){
							for(EngineLoadListener l:engine.loadListeners){
								l.setLoadingPercentage((float) .7);
							}
						}
					};
					customLoadThread.start();
					customLoadThread.join(1000);
					
					engine.mainCamera.forceRender();
					
					loadingBar.setPercentFilled((float) 0);
					loadingText.setText("Loading...");
					loadingText.centerX(engine.mainView);
					
					engine.mainCamera.forceRender();
					
					World.waitForWorldToBeReady(World.getWorldForID(engine.mainWorld.getID()), new WorldWaitListener(){
						@Override
						public void worldChecked(int totalObjects, int readyObjects) {
							Log.trace("world checked");
							loadingBar.setPercentFilled((float)((float)readyObjects/(float)totalObjects));
						}
					});
					//engine.loading = false;
					
					loadingBar.setPercentFilled(1);
					customLoadThread = new Thread(){
						public void run(){
							for(EngineLoadListener l:engine.loadListeners){
								l.setLoadingPercentage((float) 1);
							}
						}
					};
					customLoadThread.start();
					customLoadThread.join(1000);
					
					customLoadThread = new Thread(){
						public void run(){
							for(EngineLoadListener l:engine.loadListeners){
								l.waitForLoadComplete();
							}
						}
					};
					customLoadThread.start();
					customLoadThread.join(5000);
					
					engine.mainCamera.moveToWorld(engine.mainWorld.getID());
					
					//hand-off execution to master file
					Thread.currentThread().setName(masterFile.getPluginID()+"-main");
					masterFile.runGame(engine);
					
					Log.info("Master file has returned control - exiting game");
					System.exit(0);
				}
			} catch (EngineAlreadyInstancedException e) {
				e.printStackTrace();
			}
		}catch(Exception e){
			Log.fatal("Fatal Error in main(): ");
			e.printStackTrace();
			System.exit(-1);
		}
	}
	
	//Constructor
	public Engine2D(String title) throws EngineAlreadyInstancedException{
		if(!engineStarted){
			Log.info("Engine Starting - Hello World! - version: " + versionID);
			
			//Create and set up main window
			this.setIgnoreRepaint(true); //Since we are using active rendering for the graphics - ignore system calls to repaint
			this.setTitle(title); //Title the main window
			this.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); //Closing must be handled by WindowListener
			this.setSize(1080, 720);
			this.setResizable(false); //Prevents the user from manually resizing the window, and messing up all our hard work
			this.setVisible(true); //the JFrame needs to be visible to set up the BufferStrategy - will be set to invisible later until ready
			this.createBufferStrategy(2); //Set up a buffer strategy for the window - allows for better performance while rendering
			bufferStrategy = this.getBufferStrategy(); //Set the public variable so the buffer strategy can be accessed by other classes
			//this.getContentPane().add(mainView); //Add the main Frame object to the window, so we can actually draw stuff on it
			this.add(mainView);
			this.addWindowListener(this); //Tell the JFrame to send any window events to the methods below
			this.addKeyListener(this); //Tell the JFrame to send any keyboard events to the methods below
			Log.trace("Main window set up");
			
			//Set up 'mainView' frame
			mainView.setIgnoreRepaint(true); //Stop the internal frame from getting system updates as well
			mainView.resizeFrame(EngineConstants.width, EngineConstants.height); //Resize frame to the size of the window
			mainView.setBorder(null);
			this.pack();
			mainView.setTargetFPS(fpsLock); // TODO make setFPS more accurate - 60fps target results in average 62-64fps
			//mainView.unlockFPS();
			Dimension windowSize = this.getSize();
			int sideBorder = (windowSize.width - EngineConstants.width)/2; //px size of left, right, and bottom borders
			int titleBorder = (windowSize.height - EngineConstants.height)-sideBorder; //px size of top border (w/title)
			mainView.setSideBorder(sideBorder);
			mainView.setTitleBorder(titleBorder);
			Log.trace("Main viewport set up");
			
			//Set up world
			try {
				mainWorld = new World(0);
			} catch (IDAlreadyInUseException e) {
				e.printStackTrace();
			}
			Log.trace("Main world set up");
			
			//Set up camera
			mainCamera = new Camera(mainView);
			//mainCamera.showFPS(true);
			mainCamera.moveToWorld(mainWorld.getID());
			Log.trace("Main camera set up");
			
			mainCamera.showFPS(true);
			//renderLoopExecutor.scheduleAtFixedRate(renderLoop, 0, 1000000000/60, TimeUnit.NANOSECONDS); //runs loop at ~60Hz
			setFPSTarget(fpsLock);
			
			Log.trace("Render loop set up");
		}else{
			throw new EngineAlreadyInstancedException();
		}
		this.setVisible(false);
		engineReference = this; //set the static variable so that the active engine can always be instanced
	}
	
	public void setPaused(boolean b){
		paused = b;
	}
	
	public void setName(String title){
		this.setTitle(title);
	}
	
	//Returns a point (com.julianEngine.core.Point) with the location of the mouse, in the
	//julian engine coordinate space. null if not in window
	public static Point getMouseLocation(){
		java.awt.Point mousePoint = engineReference.getMousePosition(true);
		if(mousePoint!=null)
			//return new Point(mousePoint.getX(), mousePoint.getY(), 0);
			return engineReference.mainView.convertPointJGFXtoJEGFX(new Point(mousePoint.getX(), mousePoint.getY(), 0));
		return null;
	}
	
	public void showLoadBar(boolean b){
		showLoadBar = b;
	}
	
	public static Engine2D getInstance(){
		return engineReference;
	}
	
	public int getMainWorldID(){
		return mainWorld.getID();
	}
	
	public void setMainWorld(int newWorldID){
		mainWorld = World.getWorldForID(newWorldID);
	}
	
	public void setFPSLock(int targetFPS){
		mainView.setTargetFPS(targetFPS);
	}
	
	//returns an instance of the master file
	private static JDFMaster loadMasterFile() throws NoMasterDataFileFoundException, MultipleMasterFilesFoundException, MultipleMasterClassesException, NoMasterClassFoundException{
		File dataDir = new File("./Data"); //points to /Data directory
		
		//Get an array of files in the data directory that end in .jdm (Julian Data Master)
		File[] dataFiles = dataDir.listFiles(new FileFilter(){
			public boolean accept(File file){
				return file.getPath().toLowerCase().endsWith(".jdm");
			}
		});
		
		//Throw an exception if there are more than 1 .jdm files, or if there are 0 .jdm files
		if(dataFiles==null){
			throw new NoMasterDataFileFoundException();
		}else if(dataFiles.length>1){
			throw new MultipleMasterFilesFoundException();
		}
		
		//If the array consists of only 1 .jdm file
		try {
			URL[] importURL = {dataFiles[0].toURI().toURL()};
			URLClassLoader classLoader = new URLClassLoader(importURL);
			ServiceLoader<JDFMaster> masterLoader = ServiceLoader.load(JDFMaster.class, classLoader);
			Iterator<JDFMaster> masters = masterLoader.iterator();
			if(masters.hasNext()){
				JDFMaster masterFile = masters.next();
				if(masters.hasNext()){
					Log.error("Multiple master classes loaded from \""+dataFiles[0].getName()+"\"");
					throw new MultipleMasterClassesException();
				}
				return masterFile;
			}else{
				Log.error("No master class found - the .jdm file was likely compiled wrong.");
				throw new NoMasterClassFoundException();
			}
			
		} catch (MalformedURLException e) {
			Log.error("Error loading the master plugin file:");
			e.printStackTrace();
		}
		return null;
	}
	
	//returns an ArrayList of plugin files - in order based on load order - and checks for dependencies
	private static ArrayList<JDFPlugin> loadPluginFiles(){
		ArrayList<JDFPlugin> loadedPlugins = new ArrayList<JDFPlugin>();
		File dataDir = new File("./Data"); //points to /Data directory
		
		//Get an array of files in the data directory that end in .jdp (Julian Data Plugin)
		File[] dataFiles = dataDir.listFiles(new FileFilter(){
			public boolean accept(File file){
				return file.getPath().toLowerCase().endsWith(".jdp");
			}
		});
		
		for(File dataFile:dataFiles){
			try {
				URL[] importURL = {dataFile.toURI().toURL()};
				URLClassLoader classLoader = new URLClassLoader(importURL);
				ServiceLoader<JDFPlugin> masterLoader = ServiceLoader.load(JDFPlugin.class, classLoader);
				Iterator<JDFPlugin> plugins = masterLoader.iterator();
				while(plugins.hasNext()){
					loadedPlugins.add(plugins.next());
				}
				
			} catch (MalformedURLException e) {
				Log.error("Error loading the master plugin file:");
				e.printStackTrace();
			}
		}
		return loadedPlugins;
	}
	
	public BufferedImage pauseGame(){
		BufferedImage frameSnap = new BufferedImage(mainView.getWidth(), mainView.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D snapGfx = (Graphics2D)frameSnap.getGraphics();
		snapGfx.translate(-mainView.getSideBorder(), -mainView.getTitleBorder());
		mainView.drawFrame(snapGfx, true);
		return frameSnap;
	}
	
	/**
	 * Sets the fps target for the render loop
	 * @param target
	 * fps target. If less than or equal to zero, uncaps fps
	 */
	public void setFPSTarget(int target){
		renderLoopExecutor.shutdown();
		try {
			renderLoopExecutor.awaitTermination(1, TimeUnit.SECONDS);
		} catch (InterruptedException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		renderLoopExecutor = Executors.newSingleThreadScheduledExecutor();
		if(target<=0){
			renderLoopExecutor.scheduleAtFixedRate(renderLoop, 0, 1, TimeUnit.NANOSECONDS);
		}else{
			renderLoopExecutor.scheduleAtFixedRate(renderLoop, 0, 1000000000/target, TimeUnit.NANOSECONDS);
		}
	}
	
	private static void testEngine(Engine2D engine){
		try {
			World loadingScreen = new World(-1);
			
			engine.mainView.setBackground(Color.GRAY);
			Sprite background = new Sprite(new Point(0, 720, 0), 1080, 720, "./assets/images/flags/American Flag.png");
			background.setAlpha((float) .45);
			loadingScreen.addShape(background);
			
			Text nameText = new Text(new Point(100, 600, 1), "GLOBALIZATION", Color.BLACK, new Font("Ariel", Font.BOLD, 100), engine.mainView);
			loadingScreen.addShape(nameText);
			
			Text loadingText = new Text(new Point(200, 400, -1), "LOADING...", Color.BLACK, new Font("Ariel", Font.PLAIN, 50), engine.mainView);
			loadingScreen.addShape(loadingText);
		} catch (IDAlreadyInUseException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		engine.mainCamera.moveToWorld(-1);
		World.waitForWorldToBeReady(World.getWorldForID(-1));
		engine.setVisible(true);
		
		Rectangle test = new Rectangle(new Point(10,110,0), 200, 100, Color.BLACK);
		engine.mainWorld.addShape((Shape)test);
		
		Point imageTL = new Point(200, 200, 1);
		Sprite testSprite = new Sprite(imageTL, 200, 50, "./assets/images/gifs/Untitled.gif");
		testSprite.setGifFPS(10);
		engine.mainWorld.addShape(testSprite);
		
		/*
		Point textTL = new Point(500, 400, 0);
		engine.fpsText = new Text(textTL, "TESTING", Color.BLACK, new Font("Ariel", Font.PLAIN, 20), engine.mainView);
		engine.mainWorld.addShape(engine.fpsText);
		*/
		
		Point buttonTL = new Point(500, 300, 0);
		UIButton buttonTest = new UIButton(buttonTL, "Button!", Color.BLACK, engine.mainView, engine.mainWorld);
		engine.mainWorld.addShape(buttonTest);
		
		Point progressTL = new Point(200, 600, 5);
		ProgressBar progressTest = new ProgressBar(progressTL, 500, 20);
		progressTest.setBorderColor(Color.BLACK);
		progressTest.setBarColor(Color.GREEN);
		engine.mainWorld.addShape(progressTest);
		
		//System test (Health)
		Point healthBarTL = new Point(300, 500, 6);
		ProgressBar healthBar = new ProgressBar(healthBarTL, 500, 20);
		healthBar.setPercentFilled(1);
		healthBar.setBarColor(Color.RED);
		healthBar.setBorderColor(Color.ORANGE);
		Point attackButtonTL = new Point(200, 470, 6);
		UIButton attackButton = new UIButton(attackButtonTL, "Attack", Color.RED, engine.mainView, engine.mainWorld);
		attackButton.setToolTip("Attack Health Bar");
		attackButton.showToolTip(true);
		attackButton.setToolTipTimeout(1500);
		attackButton.addUIButtonListener(new UIButtonListener(){
			@Override
			public void buttonClicked() {
				Log.trace("Attacking!!");
				healthBar.setPercentFilled((float) (healthBar.getPercentFilled()-.1));
			}
			
			public void buttonMousedOver() {
			}
			public void buttonLostMouse() {
			}
		});
		
		engine.mainWorld.addShape(healthBar);
		engine.mainWorld.addShape(attackButton);
		
		boolean[][] bitMask = {{true, false, true, true, true, true, true, true, true, true}, 
								{true, true, true, true, true, true, true, true, true, true}, 
								{true, true, true, true, false, false, false, true, true, true}, 
								{true, true, true, true, false, false, false, true, true, true}, 
								{true, true, true, true, false, false, false, true, true, true}, 
								{true, true, true, true, true, true, true, true, true, true}, 
								{true, true, true, true, true, true, true, true, true, true}, 
								{true, true, true, true, true, true, true, true, true, true}, 
								{true, true, true, true, true, true, true, true, true, true}, 
								{true, true, true, true, true, true, true, true, true, true}};
		UIBitmapMask testMask = new UIBitmapMask(new Point(100, 100, 20), bitMask, engine.mainWorld);
		testMask.addUIMaskListener(new UIMaskListener(){

			@Override
			public void maskClicked() {
				Log.trace("Bitmap mask clicked!!!!!!");
			}

			@Override
			public void mouseEnteredMask() {
			}

			@Override
			public void mouseLeftMask() {
				// TODO Auto-generated method stub
				
			}
			
		});
		testMask.renderMask(true);
		engine.mainWorld.addShape(testMask);
		
		Point testPoint = new Point(10, 110, 0);
		testPoint = engine.mainView.convertPointJEGFXtoJGFX(testPoint);
		Log.trace("Calc x pos: "+testPoint.getX());
		Log.trace("Calc y pos: "+testPoint.getY());
		
		//mouse position indicator
		Point mousePosTextTL = new Point(0, 720, 0);
		Text mousePosText = new Text(mousePosTextTL, "Mouse is at: (N/A, N/A)", engine.mainView);
		
		new Thread("mouse position update thread"){
			public void run(){
				while (true){
					Point mosPos = Engine2D.getMouseLocation();
					if(mosPos!=null)
						mousePosText.setText(String.format("Mouse is at: (%01f, %01f)", mosPos.getX(), mosPos.getY()));
				}
			}
		}.start();
		engine.mainWorld.addShape(mousePosText);
		
		
		Log.info("Waiting on objects to report ready status...");
		World.waitForWorldToBeReady(engine.mainWorld);
		Log.info("Game Ready!");
		engine.mainCamera.moveToWorld(engine.mainWorld.getID());
		engine.mainView.setBackground(Color.LIGHT_GRAY);
		engine.setFPSLock(120);
		
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		Log.trace("Filling bar more");
		progressTest.setPercentFilled((float).5);
		
	}
	
	//KeyListener methods
	@Override
	public void keyTyped(KeyEvent e) {
		
	}
	
	@Override
	public void keyPressed(KeyEvent e) {
		if(!consoleActive){
			switch (e.getKeyCode()){
			case 192:
				Log.trace("Console key pressed");
				this.setPaused(true);
				consoleActive = true;
				Log.trace("Console active");
				break;
			case KeyEvent.VK_LEFT:
				mainCamera.moveCamera(new Vector(-1, 0, 0));
				mainCamera.update();
				break;
			case KeyEvent.VK_ESCAPE:
				break;
			case KeyEvent.VK_W:
				
				break;
			default:
				Log.trace("Unmapped key pressed");
			}
		}
	}

	@Override
	public void keyReleased(KeyEvent e) {
		
	}
	
	//WindowListener methods
	@Override
	public void windowOpened(WindowEvent e) {
		
	}

	@Override
	public void windowClosing(WindowEvent e) {
		Log.info("Engine shutting down");
		System.exit(0);
	}

	@Override
	public void windowClosed(WindowEvent e) {
		Log.info("Error - Window should not be able to close in this way, possibly crashed.");
	}

	@Override
	public void windowIconified(WindowEvent e) {
		
	}

	@Override
	public void windowDeiconified(WindowEvent e) {
		
	}

	@Override
	public void windowActivated(WindowEvent e) {
		
	}

	@Override
	public void windowDeactivated(WindowEvent e) {
		
	}
	
	//Exceptions
	/**
	 * Thrown when an instance of the engine already exists. Used to prevent plugins from creating
	 * multiple engines.
	 */
	public static class EngineAlreadyInstancedException extends Exception{
		private static final long serialVersionUID = 4262077084686078934L;
	}
	
	/**
	 * Thrown when there are multiple master files (.jdm) in the /data directory
	 */
	public static class MultipleMasterFilesFoundException extends Exception{
		private static final long serialVersionUID = 6452930918923379467L;
	}
	
	/**
	 * Thrown when no master file (.jdm) can be found
	 */
	public static class NoMasterDataFileFoundException extends Exception{
		private static final long serialVersionUID = 7646662446783964911L;
	}
	
	/**
	 * Thrown when multiple master classes are found in a single .jdm file
	 */
	public static class MultipleMasterClassesException extends Exception{
		private static final long serialVersionUID = 5703397513494083620L;
	}
	
	/**
	 * Thrown when no master class file is found in a .jdm file
	 */
	public static class NoMasterClassFoundException extends Exception{
		private static final long serialVersionUID = -2784167273272537183L;
	}
	
	public void addEngineLoadListener(EngineLoadListener l){
		loadListeners.add(l);
	}
	
	public interface EngineLoadListener{
		public void setLoadingPercentage(float percent);
		public void waitForLoadComplete();
	}
}