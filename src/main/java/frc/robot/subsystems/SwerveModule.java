// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot.subsystems;

import com.ctre.phoenix6.configs.CANcoderConfiguration;
import com.ctre.phoenix6.hardware.CANcoder;
import com.ctre.phoenix6.signals.AbsoluteSensorRangeValue;
import com.ctre.phoenix6.signals.SensorDirectionValue;
import com.revrobotics.CANSparkBase.ControlType;
import com.revrobotics.CANSparkLowLevel.MotorType;
import com.revrobotics.CANSparkMax;
import com.revrobotics.RelativeEncoder;
import com.revrobotics.SparkPIDController;

import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.controller.ProfiledPIDController;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.math.trajectory.TrapezoidProfile;
import frc.robot.Constants;
import frc.robot.PID;
import frc.robot.Constants.SwerveModuleConstants;

public class SwerveModule {
  private final CANSparkMax m_driveMotor;
  private final CANSparkMax m_turningMotor;

  private PID m_drivePid;

  private final SparkPIDController m_drivePidController;

  private double m_idealVelocity = 0;

  private final RelativeEncoder m_driveEncoder;
  private final CANcoder m_absoluteRotationEncoder;

  // Using a TrapezoidProfile PIDController to allow for smooth turning
  private final ProfiledPIDController m_turningPIDController =
    new ProfiledPIDController(
      SwerveModuleConstants.kTurningPID.p(),
      SwerveModuleConstants.kTurningPID.i(),
      SwerveModuleConstants.kTurningPID.d(),
      new TrapezoidProfile.Constraints(
        SwerveModuleConstants.kMaxAngularSpeedRadiansPerSecond,
        SwerveModuleConstants.kMaxAngularAccelerationRadiansPerSecondSquared));
  
  private final Rotation2d m_encoderOffset;

  /**
   * Constructs a SwerveModule.
   *
   * @param driveMotorChannel The channel of the drive motor.
   * @param turningMotorChannel The channel of the turning motor.
   * @param driveEncoderChannels The channels of the drive encoder.
   * @param turningEncoderChannels The channels of the turning encoder.
   * @param driveMotorReversed Whether the drive encoder is reversed.
   * @param turningEncoderReversed Whether the turning encoder is reversed.
   */
  public SwerveModule(
      int driveMotorChannel,
      int turningMotorChannel,
      int turningEncoderChannel,
      boolean driveMotorReversed,
      boolean turningMotorReversed,
      boolean turningEncoderReversed,
      Rotation2d encoderOffset) {
    
    m_drivePid = SwerveModuleConstants.kDrivePID;
    
    m_driveMotor = new CANSparkMax(driveMotorChannel, MotorType.kBrushless);
    m_driveMotor.restoreFactoryDefaults();
    m_driveMotor.setClosedLoopRampRate(SwerveModuleConstants.kDriveMotorRampRate);
    m_driveMotor.setInverted(driveMotorReversed);
    m_driveMotor.setSmartCurrentLimit(SwerveModuleConstants.kDriveMotorCurrentLimit);

    m_driveEncoder = m_driveMotor.getEncoder();

    // The native position units are motor rotations, but we want meters.
    final double positionConversionFactor =
      (SwerveModuleConstants.kWheelDiameterMeters * Math.PI)
      / SwerveModuleConstants.kDriveGearRatio;
    m_driveEncoder.setPositionConversionFactor(positionConversionFactor);
    
    // The native velocity units are motor rotations [aka revolutions] per minute (RPM), but we want meters per second.
    final double velocityConversionFactor =
      positionConversionFactor
      / 60.0 /* s */;
    m_driveEncoder.setVelocityConversionFactor(velocityConversionFactor);

    m_drivePidController = m_driveMotor.getPIDController();
    m_drivePidController.setFeedbackDevice(m_driveEncoder);
    m_drivePidController.setP(m_drivePid.p(), 0);
    m_drivePidController.setI(m_drivePid.i(), 0);
    m_drivePidController.setD(m_drivePid.d(), 0);
    m_drivePidController.setFF(0);

    m_turningMotor = new CANSparkMax(turningMotorChannel, MotorType.kBrushless);
    m_turningMotor.restoreFactoryDefaults();
    m_turningMotor.setClosedLoopRampRate(SwerveModuleConstants.kTurningMotorRampRate);
    m_turningMotor.setInverted(turningMotorReversed);
    m_turningMotor.setSmartCurrentLimit(SwerveModuleConstants.kTurningMotorCurrentLimit);

    m_absoluteRotationEncoder = new CANcoder(turningEncoderChannel);
    var turningEncoderConfigurator = m_absoluteRotationEncoder.getConfigurator();
    var encoderConfig = new CANcoderConfiguration();
    encoderConfig.MagnetSensor.SensorDirection = turningEncoderReversed
      ? SensorDirectionValue.Clockwise_Positive
      : SensorDirectionValue.CounterClockwise_Positive;
    encoderConfig.MagnetSensor.AbsoluteSensorRange = AbsoluteSensorRangeValue.Unsigned_0To1;
    turningEncoderConfigurator.apply(encoderConfig);
    
    // Limit the PID Controller's input range between -pi and pi and set the input
    // to be continuous.
    m_turningPIDController.enableContinuousInput(-Math.PI, Math.PI);
    m_encoderOffset = encoderOffset;
  }
  /**
   * Returns the current state of the module.
   *
   * @return The current state of the module.
   */
  public SwerveModuleState getState() {
    return new SwerveModuleState(
      m_driveEncoder.getVelocity(),
      getPosR2d()
    );
  }

  public SwerveModulePosition getPosition() {
    return new SwerveModulePosition(
      m_driveEncoder.getPosition(),
      getPosR2d()
    );
  }

  /**
   * Sets the desired state for the module.
   *
   * @param desiredState Desired state with speed and angle.
   */
  public void setDesiredState(SwerveModuleState desiredState) {
    // Optimize the reference state to avoid spinning further than 90 degrees
    SwerveModuleState state =
      SwerveModuleState.optimize(desiredState, getPosR2d());

    final double driveVelocityDesired = state.speedMetersPerSecond;
    
    m_idealVelocity = SwerveModuleConstants.kVelocityProfile.calculate(driveVelocityDesired, m_idealVelocity, Constants.kDt);
    
    // Calculate the turning motor output from the turning PID controller.
    final double turnOutput =
        MathUtil.clamp(
          m_turningPIDController.calculate(getPosR2d().getRadians(),
          state.angle.getRadians()),
           -1, 
           1
          );

    m_drivePidController.setReference(m_idealVelocity, ControlType.kVelocity);

    m_turningMotor.set(turnOutput);
  }

  /** Zeroes all the SwerveModule encoders. */
  public void resetEncoders() {
    m_driveEncoder.setPosition(0);
    m_absoluteRotationEncoder.setPosition(0);
  }

  private Rotation2d getPosR2d() {
    var absolutePositionSignal = m_absoluteRotationEncoder.getAbsolutePosition();
    Rotation2d encoderRotation = new Rotation2d(absolutePositionSignal.getValue() * 2 * Math.PI);
    return encoderRotation.minus(m_encoderOffset);
  }
}
