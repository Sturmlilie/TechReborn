/*
 * This file is part of TechReborn, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020 TechReborn
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package techreborn.blockentity.generator;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import reborncore.api.IToolDrop;
import reborncore.client.screen.BuiltScreenHandlerProvider;
import reborncore.client.screen.builder.BuiltScreenHandler;
import reborncore.client.screen.builder.ScreenHandlerBuilder;
import reborncore.common.blocks.BlockMachineBase;
import reborncore.common.powerSystem.PowerAcceptorBlockEntity;
import reborncore.common.powerSystem.PowerSystem;
import reborncore.common.util.StringUtils;
import team.reborn.energy.EnergyTier;
import techreborn.blocks.generator.BlockSolarPanel;
import techreborn.init.TRBlockEntities;
import techreborn.init.TRContent;
import techreborn.init.TRContent.SolarPanels;

import java.lang.Math;
import java.util.List;

public class SolarPanelBlockEntity extends PowerAcceptorBlockEntity implements IToolDrop, BuiltScreenHandlerProvider {


	//	State ZEROGEN: No exposure to sun
	//	State NIGHTGEN: Has direct exposure to sun
	//	State DAYGEN: Has exposure to sun and weather is sunny and not raining/thundering
	public static final int ZEROGEN = 0;
	public static final int NIGHTGEN = 1;
	public static final int DAYGEN = 2;

	private int state = ZEROGEN;
	private int prevState = ZEROGEN;

	public double incidenceAngle = 0;
	private int generationRateD = 0;
	private int updateCounter = 0;

	private SolarPanels panel;

	public SolarPanelBlockEntity() {
		super(TRBlockEntities.SOLAR_PANEL);
	}

	public SolarPanelBlockEntity(SolarPanels panel) {
		super(TRBlockEntities.SOLAR_PANEL);
		this.panel = panel;
	}

	// Normalizes radians to [0; 1]
	private static double normalizeRadians(double angle) {
		return angle / (Math.PI * 2);
	}

	// returns normalized incidence angle,
	// where 0.0 =^= 0° (no incidence) and 1.0 =^= 90° (full incidence)
	private static  double skyToIncidenceAngle(double skyAngle) {
		if (skyAngle <= 0.25) {
			// noon to evening
			return 1.0 - skyAngle * 4;
		} else if (skyAngle >= 0.75) {
			// morning to noon
			return (skyAngle - 0.75) * 4;
		}

		return 0;
	}

	private static double getIncidenceAngle(World world) {
		double angle = normalizeRadians(world.getSkyAngleRadians(0));
		return skyToIncidenceAngle(angle);
	}

	private void updatePanel() {
		if (world == null) {
			return;
		}
		Block panelBlock = world.getBlockState(pos).getBlock();
		if (panelBlock instanceof BlockSolarPanel) {
			BlockSolarPanel solarPanelBlock = (BlockSolarPanel) panelBlock;
			panel = solarPanelBlock.panelType;
		}
	}


	// Setters and getters for the GUI to sync
	private void setSunState(int state) {
		this.state = state;
	}

	public int getSunState() {
		return state;
	}

	public int getUpdateCounter() {
		return updateCounter;
	}

	public void setUpdateCounter(int v){
		updateCounter = v;
	}

	SolarPanels getPanel() {
		if (panel == null) {
			updatePanel();
		}
		return panel;
	}

	private void updateSunlightIncidence() {
		if (this.getSunState() == DAYGEN) {
			incidenceAngle = getIncidenceAngle(world);
			// Generation rates follow the sine of the lightray incidence angle
			final double incidenceFactor = Math.sin((Math.PI/2)*incidenceAngle);
			// Round to the nearest whole number
			final int generation = (int) Math.round(getPanel().generationRateD * incidenceFactor);
			// Make sure that day generation never drops below night generation rates, for consistency
			generationRateD = Math.max(generation, getPanel().generationRateN);
			// Hack to update GUI on light incidence change
			++updateCounter;
		} else {
			incidenceAngle = 0;
			generationRateD = getPanel().generationRateN;
		}
	}

	private void updateState() {
		if (world == null) {
			return;
		}
		if (world.isSkyVisible(pos.up())) {
			this.setSunState(NIGHTGEN);

			if (!world.isRaining() && !world.isThundering() && world.isDay()) {
				this.setSunState(DAYGEN);
			}
		} else {
			this.setSunState(ZEROGEN);
		}
		// Nether and The End
		if (!world.getDimension().hasSkyLight()) {
			this.setSunState(NIGHTGEN);
		}

		if (prevState != this.getSunState()) {
			boolean isGenerating = getSunState() == DAYGEN;

			world.setBlockState(pos, world.getBlockState(pos).with(BlockMachineBase.ACTIVE, isGenerating));

			prevState = this.getSunState();
		}

		this.updateSunlightIncidence();
	}

	private boolean isServer() {
		if (this.world == null) {
			return false;
		}

		return !this.world.isClient;
	}

	public int getGenerationRate() {
		int rate = 0;

		switch (getSunState()) {
			case DAYGEN:
				if (!isServer()) {
					this.updateSunlightIncidence();
				}

				rate = generationRateD;
				break;
			case NIGHTGEN:
				rate = getPanel().generationRateN;
		}

		return rate;
	}


	// Overrides

	@Override
	public void tick() {
		super.tick();

		if (world == null) {
			return;
		}

		if (world.isClient) {
			return;
		}

		if (getPanel() == TRContent.SolarPanels.CREATIVE) {
			checkOverfill = false;
			setEnergy(Integer.MAX_VALUE);
			return;
		}

		// State checking and updating
		if (world.getTime() % 20 == 0) {
			updateState();
		}

		// Power generation calculations
		addEnergy(getGenerationRate());
	}

	@Override
	public double getBaseMaxPower() {
		return getPanel().internalCapacity;
	}

	@Override
	public boolean canAcceptEnergy(final Direction direction) {
		return false;
	}

	@Override
	public boolean canProvideEnergy(final Direction direction) {
		return true;
	}

	@Override
	public double getBaseMaxOutput() {
		if (getPanel() == TRContent.SolarPanels.CREATIVE) {
			return EnergyTier.INSANE.getMaxOutput();
		}
		// Solar panel output will only be limited by the cables the users use
		return EnergyTier.EXTREME.getMaxOutput();
	}

	@Override
	public double getBaseMaxInput() {
		return 0;
	}

	@Override
	public boolean canBeUpgraded() {
		return false;
	}

	@Override
	public boolean hasSlotConfig() {
		return false;
	}

	@Override
	public EnergyTier getTier() {
		return getPanel().powerTier;
	}

	@Override
	public void checkTier() {
		// Nope
	}

	@Override
	public void addInfo(List<Text> info, boolean isReal, boolean hasData) {
		if (panel == SolarPanels.CREATIVE) {
			return;
		}

		info.add(
				new TranslatableText("reborncore.tooltip.energy.maxEnergy")
						.formatted(Formatting.GRAY)
						.append(": ")
						.append(
								new LiteralText(PowerSystem.getLocaliszedPowerFormatted(getMaxPower()))
										.formatted(Formatting.GOLD)
						)
		);

		info.add(
				new TranslatableText("techreborn.tooltip.generationRate.day")
						.formatted(Formatting.GRAY)
						.append(": ")
						.append(
								new LiteralText(PowerSystem.getLocaliszedPowerFormatted(panel.generationRateD))
										.formatted(Formatting.GOLD)
						)
		);

		info.add(
				new TranslatableText("techreborn.tooltip.generationRate.night")
						.formatted(Formatting.GRAY)
						.append(": ")
						.append(
								new LiteralText(PowerSystem.getLocaliszedPowerFormatted(panel.generationRateN))
										.formatted(Formatting.GOLD)
						)
		);

		info.add(
				new TranslatableText("reborncore.tooltip.energy.tier")
						.formatted(Formatting.GRAY)
						.append(": ")
						.append(
								new LiteralText(StringUtils.toFirstCapitalAllLowercase(getTier().toString()))
										.formatted(Formatting.GOLD)
						)
		);
	}

	@Override
	public void fromTag(BlockState blockState, CompoundTag tag) {
		if (world == null) {
			// We are in BlockEntity.create method during chunk load.
			this.checkOverfill = false;
		}
		updatePanel();
		super.fromTag(blockState, tag);
	}

	// MachineBaseBlockEntity
	@Override
	public void onLoad() {
		super.onLoad();
		updatePanel();
	}

	// IToolDrop
	@Override
	public ItemStack getToolDrop(final PlayerEntity playerIn) {
		return new ItemStack(getBlockType());
	}

	@Override
	public BuiltScreenHandler createScreenHandler(int syncID, final PlayerEntity player) {
		return new ScreenHandlerBuilder("solar_panel").player(player.inventory).inventory().hotbar().addInventory()
				.blockEntity(this).syncEnergyValue()
				.sync(this::getSunState, this::setSunState)
				.sync(this::getUpdateCounter, this::setUpdateCounter)
				.addInventory().create(this, syncID);
	}
}
