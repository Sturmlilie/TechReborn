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
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Direction;
import reborncore.api.IToolDrop;
import reborncore.client.containerBuilder.IContainerProvider;
import reborncore.client.containerBuilder.builder.BuiltContainer;
import reborncore.client.containerBuilder.builder.ContainerBuilder;
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

public class SolarPanelBlockEntity extends PowerAcceptorBlockEntity implements IToolDrop, IContainerProvider {
private String lgp() {
		if (this.world == null) {
			return "[N] ";
		}

		if (this.world.isClient) {
			return "[C] ";
		} else {
			return "[S] ";
		}
	}

	private boolean isServer() {
		if (this.world == null) {
			return false;
		}
		return !this.world.isClient;
	}

	private int objId(Object obj) {
		if (obj == null) {
			return 0;
		} else {
			return obj.hashCode();
		}
	}

	// returns normalized incidence angle,
	// where 0.0 =^= 0° (no incidence) and 1.0 =^= 90° (full incidence)
	private double skyToIncidenceAngle(double skyAngle) {
		//~ final double quarterCircle = Math.PI / 2;
		final double quarterCircle = 90;
		if (skyAngle <= 0.25) {
			// noon to evening
			return 1.0 - skyAngle * 4;
		} else if (skyAngle >= 0.75) {
			// morning to noon
			return (skyAngle - 0.75) * 4;
		}

		return 0;
	}

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
	public int getUpdateCounter(){return updateCounter;}public void setUpdateCounter(int v){updateCounter=v;}

	private SolarPanels panel;

	public SolarPanelBlockEntity(SolarPanels panel) {
		super(TRBlockEntities.SOLAR_PANEL);
		this.panel = panel;
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
		if (this.state != DAYGEN && state == DAYGEN) {
			//~ System.out.printf(lgp()+"+++ [%X] transition to DAYGEN at %d\n", objId(this), world
		}

		this.state = state;
	}

	public int getSunState() {
		return state;
	}

	SolarPanels getPanel() {
		if (panel == null) {
			updatePanel();
		}
		return panel;
	}

	private void updateSunlightIncidence() {
		if (this.getSunState() == DAYGEN) {
			incidenceAngle = skyToIncidenceAngle(world.getSkyAngle(0));
			final double incidenceFactor = Math.sin((Math.PI/2)*incidenceAngle);
			final int generation = (int) Math.round(getPanel().generationRateD * incidenceFactor);
			generationRateD = Math.max(generation, getPanel().generationRateN);
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
		System.out.printf(lgp()+"+++ [%X] sky angle: %f | sun angle: %f\n",
		objId(this), this.world.getSkyAngle(0), skyToIncidenceAngle(this.world.getSkyAngle(0)));
		if (world.isSkyVisible(pos.up())) {
			this.setSunState(NIGHTGEN);

			if (!world.isRaining() && !world.isThundering() && world.isDay()) {
				this.setSunState(DAYGEN);
			}
		} else {
			this.setSunState(ZEROGEN);
		}
		// Nether and The End
		if (!world.dimension.hasSkyLight()) {
			this.setSunState(NIGHTGEN);
		}

		if (prevState != this.getSunState()) {
			boolean isGenerating = getSunState() == DAYGEN;

			world.setBlockState(pos, world.getBlockState(pos).with(BlockMachineBase.ACTIVE, isGenerating));

			prevState = this.getSunState();
		}

		this.updateSunlightIncidence();
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
//~ System.out.printf(lgp()+"+++ [%X]#getGenerationRate() -> %d\n", objId(this), rate);
		return rate;
	}


	// Overrides

	@Override
	public void tick() {
		super.tick();

		if (world == null){
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
		if (getPanel() == TRContent.SolarPanels.CREATIVE){
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
		info.add(new LiteralText(Formatting.GRAY + StringUtils.t("reborncore.tooltip.energy.maxEnergy") + ": "
				+ Formatting.GOLD + PowerSystem.getLocaliszedPowerFormatted(getMaxPower())));

		info.add(new LiteralText(Formatting.GRAY + StringUtils.t("techreborn.tooltip.generationRate.day") + ": "
				+ Formatting.GOLD + PowerSystem.getLocaliszedPowerFormatted(panel.generationRateD)));

		info.add(new LiteralText(Formatting.GRAY + StringUtils.t("techreborn.tooltip.generationRate.night") + ": "
				+ Formatting.GOLD + PowerSystem.getLocaliszedPowerFormatted(panel.generationRateN)));

		info.add(new LiteralText(Formatting.GRAY + StringUtils.t("reborncore.tooltip.energy.tier") + ": "
				+ Formatting.GOLD + StringUtils.toFirstCapitalAllLowercase(getTier().toString())));
	}

	@Override
	public void fromTag(CompoundTag tag) {
		if (world == null) {
			// We are in BlockEntity.create method during chunk load.
			this.checkOverfill = false;
		}
		updatePanel();
		super.fromTag(tag);
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
	public BuiltContainer createContainer(int syncID, final PlayerEntity player) {
		return new ContainerBuilder("solar_panel").player(player.inventory).inventory().hotbar().addInventory()
				.blockEntity(this).syncEnergyValue()
				.sync(this::getSunState, this::setSunState)
				.sync(this::getUpdateCounter, this::setUpdateCounter)
				.addInventory().create(this, syncID);
	}
}
