package org.matsim.run;

import com.google.inject.Key;
import com.google.inject.name.Names;
import org.matsim.analysis.QsimTimingModule;
import org.matsim.analysis.personMoney.PersonMoneyEventsAnalysisModule;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimApplication;
import org.matsim.application.options.SampleOptions;
import org.matsim.contrib.bicycle.BicycleConfigGroup;
import org.matsim.contrib.bicycle.BicycleLinkSpeedCalculator;
import org.matsim.contrib.bicycle.BicycleLinkSpeedCalculatorDefaultImpl;
import org.matsim.contrib.bicycle.BicycleTravelTime;
import org.matsim.contrib.emissions.HbefaRoadTypeMapping;
import org.matsim.contrib.emissions.OsmHbefaMapping;
import org.matsim.contrib.emissions.utils.EmissionsConfigGroup;
import org.matsim.contrib.multimodal.router.util.WalkTravelTime;
import org.matsim.contrib.parking.parkingchoice.PC2.GeneralParkingModule;
import org.matsim.contrib.parking.parkingchoice.PC2.infrastructure.PC2Parking;
import org.matsim.contrib.parking.parkingchoice.PC2.infrastructure.PublicParking;
import org.matsim.contrib.parking.parkingchoice.PC2.scoring.AbstractParkingBetas;
import org.matsim.contrib.parking.parkingchoice.PC2.scoring.ParkingScoreManager;
import org.matsim.contrib.parking.parkingchoice.PC2.simulation.ParkingInfrastructureManager;
import org.matsim.contrib.vsp.scoring.RideScoringParamsFromCarParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.*;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutilityFactory;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.run.scoring.AdvancedScoringConfigGroup;
import org.matsim.run.scoring.AdvancedScoringModule;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.SimWrapperModule;
import picocli.CommandLine;
import playground.vsp.scoring.IncomeDependentUtilityOfMoneyPersonScoringParameters;

import java.util.List;


@CommandLine.Command(header = ":: Open Berlin Scenario ::", version = PMCOpenBerlinScenario.VERSION, mixinStandardHelpOptions = true, showDefaultValues = true)
public class PMCOpenBerlinScenario extends MATSimApplication {

	public static final String VERSION = "6.4";
	public static final String CRS = "EPSG:25832";
	// Micro-car mode added
	public static final String MICRO_CAR = "micro_car";

	//	To decrypt hbefa input files set MATSIM_DECRYPTION_PASSWORD as environment variable. ask VSP for access.
	private static final String HBEFA_2020_PATH = "https://svn.vsp.tu-berlin.de/repos/public-svn/3507bb3997e5657ab9da76dbedbb13c9b5991d3e/0e73947443d68f95202b71a156b337f7f71604ae/";
	private static final String HBEFA_FILE_COLD_DETAILED = HBEFA_2020_PATH + "82t7b02rc0rji2kmsahfwp933u2rfjlkhfpi2u9r20.enc";
	private static final String HBEFA_FILE_WARM_DETAILED = HBEFA_2020_PATH + "944637571c833ddcf1d0dfcccb59838509f397e6.enc";
	private static final String HBEFA_FILE_COLD_AVERAGE = HBEFA_2020_PATH + "r9230ru2n209r30u2fn0c9rn20n2rujkhkjhoewt84202.enc" ;
	private static final String HBEFA_FILE_WARM_AVERAGE = HBEFA_2020_PATH + "7eff8f308633df1b8ac4d06d05180dd0c5fdf577.enc";

	@CommandLine.Mixin
	private final SampleOptions sample = new SampleOptions(10, 25, 3, 1);

	@CommandLine.Option(names = "--plan-selector",
		description = "Plan selector to use.",
		defaultValue = DefaultPlanStrategiesModule.DefaultSelector.ChangeExpBeta)
	private String planSelector;

	public PMCOpenBerlinScenario() {
		super(String.format("input/v%s/berlin-v%s.config.xml", VERSION, VERSION));
	}

	public static void main(String[] args) {
		MATSimApplication.run(PMCOpenBerlinScenario.class, args);
	}

	@Override
	protected Config prepareConfig(Config config) {

		SimWrapperConfigGroup sw = ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class);

		if (sample.isSet()) {
			double sampleSize = sample.getSample();

			config.qsim().setFlowCapFactor(sampleSize);
			config.qsim().setStorageCapFactor(sampleSize);

			// Counts can be scaled with sample size
			config.counts().setCountsScaleFactor(sampleSize);
			sw.sampleSize = sampleSize;

			config.controller().setRunId(sample.adjustName(config.controller().getRunId()));
			config.controller().setOutputDirectory(sample.adjustName(config.controller().getOutputDirectory()));
			config.plans().setInputFile(sample.adjustName(config.plans().getInputFile()));
		}

		config.qsim().setUsingTravelTimeCheckInTeleportation(true);

		// overwrite ride scoring params with values derived from car
		RideScoringParamsFromCarParams.setRideScoringParamsBasedOnCarParams(config.scoring(), 1.0);
		Activities.addScoringParams(config, true);


		// Register micro_car as a main (routing) mode
		QSimConfigGroup qsim = config.qsim();
		List<String> mainModes = new java.util.ArrayList<>(qsim.getMainModes());
		if (!mainModes.contains(MICRO_CAR)) mainModes.add(MICRO_CAR);
		qsim.setMainModes(mainModes);

		// --- [MC] route micro_car on the car network (and make it a network mode) ---
		RoutingConfigGroup rc = config.routing();
		List<String> networkModes = new java.util.ArrayList<>(rc.getNetworkModes());
		if (!networkModes.contains(MICRO_CAR)) networkModes.add(MICRO_CAR);
		rc.setNetworkModes(networkModes);



		// Copy scoring params from car
		ScoringConfigGroup.ModeParams car = config.scoring().getOrCreateModeParams(TransportMode.car);
		ScoringConfigGroup.ModeParams mc = config.scoring().getOrCreateModeParams(MICRO_CAR);
		mc.setConstant(car.getConstant());
		mc.setMarginalUtilityOfTraveling(car.getMarginalUtilityOfTraveling());
		mc.setMarginalUtilityOfDistance(car.getMarginalUtilityOfDistance());
		mc.setMonetaryDistanceRate(car.getMonetaryDistanceRate());



		// Required for all calibration strategies
		for (String subpopulation : List.of("person", "potMCUser", "freight", "goodsTraffic", "commercialPersonTraffic", "commercialPersonTraffic_service")) {
			config.replanning().addStrategySettings(
				new ReplanningConfigGroup.StrategySettings()
					.setStrategyName(planSelector)
					.setWeight(1.0)
					.setSubpopulation(subpopulation)
			);

			config.replanning().addStrategySettings(
				new ReplanningConfigGroup.StrategySettings()
					.setStrategyName(DefaultPlanStrategiesModule.DefaultStrategy.ReRoute)
					.setWeight(1.0)
					.setSubpopulation("person")
			);
		}

		config.replanning().addStrategySettings(
			new ReplanningConfigGroup.StrategySettings()
				.setStrategyName(DefaultPlanStrategiesModule.DefaultStrategy.ReRoute)
				.setWeight(1.0)
				.setSubpopulation("potMCUser")
		);

//		config.replanning().addStrategySettings(
//			new ReplanningConfigGroup.StrategySettings()
//				.setStrategyName(DefaultPlanStrategiesModule.DefaultStrategy.SubtourModeChoice)
//				.setWeight(0.15)
//				.setSubpopulation("potMCUser")
//		);

//		// [MC] ensure SubtourModeChoice knows micro_car (only if SMC is active)
//		ConfigUtils.addOrGetModule(config, org.matsim.core.config.groups.SubtourModeChoiceConfigGroup.class).setModes(List.of("walk", "bike", "pt", "car", MICRO_CAR).toArray(new String[0]));
//		ConfigUtils.addOrGetModule(config, org.matsim.core.config.groups.SubtourModeChoiceConfigGroup.class).setChainBasedModes(List.of("bike", "car", MICRO_CAR).toArray(new String[0]));


		// Need to switch to warning for best score
		if (planSelector.equals(DefaultPlanStrategiesModule.DefaultSelector.BestScore)) {
			config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.warn);
		}

		// Bicycle config must be present
		ConfigUtils.addOrGetModule(config, BicycleConfigGroup.class);

		// Add emissions configuration
		EmissionsConfigGroup eConfig = ConfigUtils.addOrGetModule(config, EmissionsConfigGroup.class);
		eConfig.setDetailedColdEmissionFactorsFile(HBEFA_FILE_COLD_DETAILED);
		eConfig.setDetailedWarmEmissionFactorsFile(HBEFA_FILE_WARM_DETAILED);
		eConfig.setAverageColdEmissionFactorsFile(HBEFA_FILE_COLD_AVERAGE);
		eConfig.setAverageWarmEmissionFactorsFile(HBEFA_FILE_WARM_AVERAGE);
		eConfig.setHbefaTableConsistencyCheckingLevel(EmissionsConfigGroup.HbefaTableConsistencyCheckingLevel.consistent);
		eConfig.setDetailedVsAverageLookupBehavior(EmissionsConfigGroup.DetailedVsAverageLookupBehavior.tryDetailedThenTechnologyAverageThenAverageTable);
		eConfig.setEmissionsComputationMethod(EmissionsConfigGroup.EmissionsComputationMethod.StopAndGoFraction);

		return config;
	}

	// This whole block is new. To add Micro_car to Network
	@Override
	protected void prepareScenario(Scenario scenario) {

		// existing: add HBEFA link attributes
		HbefaRoadTypeMapping roadTypeMapping = OsmHbefaMapping.build();
		roadTypeMapping.addHbefaMappings(scenario.getNetwork());

		// --- make micro_car usable on the car network ---
		scenario.getNetwork().getLinks().values().forEach(link -> {
			java.util.Set<String> allowedModes = link.getAllowedModes();
			if (allowedModes != null && allowedModes.contains(TransportMode.car)) {
				java.util.Set<String> newModes = new java.util.HashSet<>(allowedModes);
				newModes.add(MICRO_CAR);
				link.setAllowedModes(newModes);
			}
			// if allowedModes == null: treat as unrestricted; no change needed
		});
	}


	@Override
	protected void prepareControler(Controler controler) {

		controler.addOverridingModule(new SimWrapperModule());
		//controler.addOverridingModule(new TravelTimeBinding());
		controler.addOverridingModule(new QsimTimingModule());



		// AdvancedScoring is specific to matsim-berlin!
//		if (ConfigUtils.hasModule(controler.getConfig(), AdvancedScoringConfigGroup.class)) {
//			controler.addOverridingModule(new AdvancedScoringModule());
//			controler.getConfig().scoring().setExplainScores(true);
//		} else {
//			// if the above config group is not present we still need income dependent scoring
//			// this implementation also allows for person specific asc
//			controler.addOverridingModule(new AbstractModule() {
//				@Override
//				public void install() {
//					bind(ScoringParametersForPerson.class).to(IncomeDependentUtilityOfMoneyPersonScoringParameters.class).asEagerSingleton();
//				}
//			});
//		}
//		controler.addOverridingModule(new PersonMoneyEventsAnalysisModule());


		// --------------------------- PC2 PARKING INTEGRATION (minimal) ---------------------------
		// 1) Install the general parking module (wires PC2 handlers & listeners)



		// 2) Create the parking score manager using walk travel time for access/egress
		ParkingScoreManager parkingScoreManager =
			new ParkingScoreManager(new WalkTravelTime(controler.getConfig().routing()), controler.getScenario());

		parkingScoreManager.setParkingScoreScalingFactor(1);
		parkingScoreManager.setParkingBetas(new AbstractParkingBetas() {
			@Override
			public double getParkingWalkBeta(Person person, double activityDurationInSeconds) {
				return 0;
			}

			@Override
			public double getParkingCostBeta(Person person) {
				return 0;
			}
		});



		// We will remove this with csv read parking infra
		// 3) Create the parking infrastructure manager and register public parkings
		ParkingInfrastructureManager parkingInfrastructureManager =
			new ParkingInfrastructureManager(parkingScoreManager, controler.getEvents());

		java.util.LinkedList<PublicParking> publicParkings = new java.util.LinkedList<>();
		// Example parking near "work"
		publicParkings.add(new PublicParking(
			Id.create("workPark", PC2Parking.class),
			98,
			new Coord(10_000, 0),
			new ParkingCostCalculatorExample(1),
			"park"
		));
		// Example parking near "home"
		publicParkings.add(new PublicParking(
			Id.create("homePark", PC2Parking.class),
			98,
			new Coord(-25_000, 0),
			new ParkingCostCalculatorExample(0),
			"park"
		));
		parkingInfrastructureManager.setPublicParkings(publicParkings);


		//setting up the Parking Module
		GeneralParkingModule generalParkingModule = new GeneralParkingModule(controler);
		generalParkingModule.setParkingScoreManager(parkingScoreManager);
		generalParkingModule.setParkingInfrastructurManager(parkingInfrastructureManager);

		// 4) Ensure event handling is active (GeneralParkingModule wires most pieces; this is safe & explicit)
		//controler.getEvents().addHandler(parkingInfrastructureManager);
		// -----------------------------------------------------------------------------------------
	}






	/**
	 * Add travel time bindings for ride and freight modes, which are not actually network modes.
	 */
//	public static final class TravelTimeBinding extends AbstractModule {
//
//		private final boolean carOnly;
//
//		public TravelTimeBinding() {
//			this.carOnly = false;
//		}
//
//		public TravelTimeBinding(boolean carOnly) {
//			this.carOnly = carOnly;
//		}
//
//		@Override
//		public void install() {
//			addTravelTimeBinding(TransportMode.ride).to(networkTravelTime());
//			addTravelDisutilityFactoryBinding(TransportMode.ride).to(carTravelDisutilityFactoryKey());
//
//			// Add micro car to TravelTimeBinding
//			addTravelTimeBinding(MICRO_CAR).to(networkTravelTime());
//			addTravelDisutilityFactoryBinding(MICRO_CAR).to(carTravelDisutilityFactoryKey());
//
//			if (!carOnly) {
//				addTravelTimeBinding("freight").to(Key.get(TravelTime.class, Names.named(TransportMode.truck)));
//				addTravelDisutilityFactoryBinding("freight").to(Key.get(TravelDisutilityFactory.class, Names.named(TransportMode.truck)));
//
//
//				bind(BicycleLinkSpeedCalculator.class).to(BicycleLinkSpeedCalculatorDefaultImpl.class);
//
//				// Bike should use free speed travel time
//				addTravelTimeBinding(TransportMode.bike).to(BicycleTravelTime.class);
//				addTravelDisutilityFactoryBinding(TransportMode.bike).to(OnlyTimeDependentTravelDisutilityFactory.class);
//			}
//		}
//	}

}
