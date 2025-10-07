package org.matsim.run;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.parking.parkingchoice.PC2.scoring.ParkingCostModel;

class ParkingCostCalculatorExample implements ParkingCostModel {

	private double hourlyParkingCharge;

	ParkingCostCalculatorExample(double hourlyParkingCharge) {
		this.hourlyParkingCharge = hourlyParkingCharge;
	}

	@Override
	public double calcParkingCost(double arrivalTimeInSeconds, double durationInSeconds, Id<Person> personId, Id parkingFacilityId) {

		return hourlyParkingCharge*(durationInSeconds/3600);

	}

}
