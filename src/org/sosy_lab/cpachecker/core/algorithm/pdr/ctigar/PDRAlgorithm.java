/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2016  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.core.algorithm.pdr.ctigar;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.logging.Level;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.io.IO;
import org.sosy_lab.common.io.TempFile;
import org.sosy_lab.common.io.TempFile.DeleteOnCloseFile;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.time.Timer;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.CoreComponentsFactory;
import org.sosy_lab.cpachecker.core.Specification;
import org.sosy_lab.cpachecker.core.algorithm.Algorithm;
import org.sosy_lab.cpachecker.core.algorithm.CPAAlgorithm;
import org.sosy_lab.cpachecker.core.algorithm.pdr.ctigar.PDRSmt.ConsecutionResult;
import org.sosy_lab.cpachecker.core.algorithm.pdr.transition.Block;
import org.sosy_lab.cpachecker.core.algorithm.pdr.transition.ForwardTransition;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.StateSpacePartition;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.interfaces.StatisticsProvider;
import org.sosy_lab.cpachecker.core.reachedset.AggregatedReachedSets;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSet;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSetFactory;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.cpa.arg.ARGUtils;
import org.sosy_lab.cpachecker.cpa.arg.path.ARGPath;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateCPA;
import org.sosy_lab.cpachecker.exceptions.CPAEnabledAnalysisPropertyViolationException;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.exceptions.CPATransferException;
import org.sosy_lab.cpachecker.util.AbstractStates;
import org.sosy_lab.cpachecker.util.CPAs;
import org.sosy_lab.cpachecker.util.predicates.pathformula.PathFormulaManager;
import org.sosy_lab.cpachecker.util.predicates.smt.BooleanFormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.smt.FormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.smt.Solver;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.Model.ValueAssignment;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.api.SolverContext.ProverOptions;
import org.sosy_lab.java_smt.api.SolverException;

/**
 * Property Directed Reachability algorithm, also known as IC3. It can be used to check whether a
 * program is safe or not.
 */
public class PDRAlgorithm implements Algorithm, StatisticsProvider {

  private final CFA cfa;
  private final Solver solver;
  private final PredicateCPA predCPA;
  private final FormulaManagerView fmgr;
  private final BooleanFormulaManagerView bfmgr;
  private final PathFormulaManager pfmgr;
  private final LogManager logger;
  private final ShutdownNotifier shutdownNotifier;
  private final ForwardTransition stepwiseTransition;
  private final Algorithm algorithm;
  private final Configuration config;
  private final PDROptions optionsCollection;
  private final StatisticsDelegator compositeStats;
  private final Specification specification;

  // Those are null until initialized in run()
  private @Nullable PDRStatistics stats;
  private @Nullable TransitionSystem transition;
  private @Nullable PredicatePrecisionManager predicateManager;
  private @Nullable FrameSet frameSet;
  private @Nullable PDRSmt pdrSolver;

  /**
   * Creates a new PDRAlgorithm instance.
   *
   * @param pReachedSetFactory Used for creating temporary reached sets for stepwise analysis.
   * @param pCPA The composite CPA that contains all needed CPAs.
   * @param pAlgorithm The algorithm used for traversing the CFA.
   * @param pCFA The program's control-flow automaton.
   * @param pConfig The configuration that contains the components and options for this algorithm.
   * @param pLogger The logging component.
   * @param pShutdownNotifier The notifier that is used to shutdown this algorithm if necessary.
   * @param pSpecification The specification of the verification task.
   * @throws InvalidConfigurationException If the configuration file is invalid or incomplete.
   */
  public PDRAlgorithm(
      ReachedSetFactory pReachedSetFactory,
      ConfigurableProgramAnalysis pCPA,
      Algorithm pAlgorithm,
      CFA pCFA,
      Configuration pConfig,
      LogManager pLogger,
      ShutdownNotifier pShutdownNotifier,
      Specification pSpecification)
      throws InvalidConfigurationException {

    cfa = Objects.requireNonNull(pCFA);
    algorithm = Objects.requireNonNull(pAlgorithm);
    config = Objects.requireNonNull(pConfig);
    optionsCollection = new PDROptions(config);

    predCPA =
        CPAs.retrieveCPAOrFail(
            Objects.requireNonNull(pCPA), PredicateCPA.class, PDRAlgorithm.class);
    solver = predCPA.getSolver();
    fmgr = solver.getFormulaManager();
    bfmgr = fmgr.getBooleanFormulaManager();
    pfmgr = predCPA.getPathFormulaManager();
    shutdownNotifier = Objects.requireNonNull(pShutdownNotifier);
    logger = Objects.requireNonNull(pLogger);
    compositeStats = new StatisticsDelegator("PDR related", pLogger);
    stats = new PDRStatistics();
    compositeStats.register(stats);
    stepwiseTransition =
        new ForwardTransition(Objects.requireNonNull(pReachedSetFactory), pCPA, pAlgorithm, cfa);
    specification = Objects.requireNonNull(pSpecification);

    // Initialized in run()
    transition = null;
    predicateManager = null;
    frameSet = null;
    pdrSolver = null;
  }

  /**
   * Checks if any target-location can be directly reached from the given CFANode in 0 or 1 step.
   * One step is defined by the transition-encoding of the stepwise transition.
   */
  private boolean checkBaseCases(CFANode pMainEntry, ReachedSet pReachedSet)
      throws SolverException, InterruptedException, CPAException {

    Set<CFANode> targetLocations = transition.getTargetLocations();

    // For trivially safe programs.
    if (targetLocations.isEmpty()) {
      logger.log(Level.INFO, "No target-locations found. Program is trivially safe.");
      return true;
    }

    // Check for 0-step counterexample.
    if (targetLocations.contains(pMainEntry)) {
      logger.log(Level.INFO, "Found errorpath: Starting location is a target-location.");
      return false;
    }

    // Check for 1-step counterexample: Is there a satisfiable block-transition from the start-location
    // to any target-location?
    for (Block blockToError :
        stepwiseTransition
            .getBlocksFrom(pMainEntry)
            .filter(b -> targetLocations.contains(b.getSuccessorLocation()))) {
      if (!solver.isUnsat(blockToError.getFormula())) {
        logger.log(Level.INFO, "Found errorpath: 1-step counterexample.");
        analyzeCounterexample(
            Collections.singletonList(new BlockWithConcreteState(blockToError, bfmgr.makeTrue())),
            pReachedSet);
        return false;
      }
    }

    return true;
  }

  /** Resets everything that needs to be fresh for each new run. */
  private void prepareComponentsForNewRun() {
    compositeStats.unregisterAll();
    stats = new PDRStatistics();
    compositeStats.register(stats);
    frameSet = new DeltaEncodedFrameSet(solver, fmgr, transition, compositeStats);
    predicateManager =
        new PredicatePrecisionManager(
            fmgr, predCPA.getPredicateManager(), pfmgr, transition, cfa, compositeStats);
    pdrSolver =
        new PDRSmt(
            frameSet,
            solver,
            fmgr,
            pfmgr,
            predicateManager,
            transition,
            compositeStats,
            logger,
            stepwiseTransition,
            optionsCollection,
            shutdownNotifier);
  }

  @Override
  public AlgorithmStatus run(ReachedSet pReachedSet) throws CPAException, InterruptedException {
    CFANode mainEntry =
        FluentIterable.from(pReachedSet).transform(AbstractStates.EXTRACT_LOCATION).first().get();
    pReachedSet.clear();

    // Only need to create this at first run.
    if (transition == null) {
      transition = new TransitionSystem(cfa, stepwiseTransition, fmgr, pfmgr, mainEntry);
    }

    try {
      if (!checkBaseCases(mainEntry, pReachedSet)) {
        return AlgorithmStatus.SOUND_AND_PRECISE; // cex found
      }
      prepareComponentsForNewRun();

      // No 0-/1-step cex. We can set F_0 to the initial condition and F_1 to the safety property.
      frameSet.openNextFrame();
      logger.log(Level.INFO, "New frontier : ", frameSet.getFrontierLevel());

      /*
       * Main loop : Try to inductively strengthen highest frame set, propagate
       * states afterwards and check for termination.
       */
      while (!shutdownNotifier.shouldShutdown()) {
        if (!strengthen(pReachedSet)) {
          logger.log(Level.INFO, "Found errorpath. Program has a bug.");
          return AlgorithmStatus.SOUND_AND_PRECISE;
        }

        /*
         *  No state in current frontier frame can 1-step transition to an error-state.
         *  Advance frontier by one and push states forward.
         */
        frameSet.openNextFrame();
        logger.log(Level.INFO, "New frontier : ", frameSet.getFrontierLevel());
        logger.log(Level.INFO, "Starting propagation.");

        if (frameSet.propagate(shutdownNotifier)) {

          // All reachable states are found and they satisfy the safety property.
          logger.log(Level.INFO, "Program is safe.");
          return AlgorithmStatus.SOUND_AND_PRECISE;
        }
        shutdownNotifier.shutdownIfNecessary();
      }
    } catch (SolverException e) {
      logger.logException(Level.WARNING, e, null);
      throw new CPAException("Solver error.", e);
    }

    throw new AssertionError("Could neither prove nor disprove safety of program.");
  }

  /**
   * Tries to prove that a target-location cannot be reached with a number of steps less or equal to
   * 1 + {@link FrameSet#getFrontierLevel()}. Any state that can reach a target-location in that
   * amount of steps will be proved unreachable. If this isn't possible, a counterexample trace is
   * created.
   *
   * @return True if all states able to reach a target-location in 1 + {@link
   *     FrameSet#getFrontierLevel()} steps could be blocked. False if a counterexample is found.
   */
  private boolean strengthen(ReachedSet pReached)
      throws InterruptedException, SolverException, CPAEnabledAnalysisPropertyViolationException,
          CPAException {

    // Ask for states with direct transition to any target-location (Counterexample To Inductiveness)
    Optional<ConsecutionResult> cti = pdrSolver.getCTIinFrontierFrame();

    // Recursively block all discovered CTIs
    while (cti.isPresent()) {
      StatesWithLocation badStates = cti.get().getResult();

      if (!backwardblock(badStates, pReached)) {
        return false;
      }
      cti = pdrSolver.getCTIinFrontierFrame(); // Ask for next CTI
      shutdownNotifier.shutdownIfNecessary();
    }
    return true;
  }

  /**
   * Tries to prove by induction relative to the frontier frame in the frame set that the given
   * states are unreachable. If predecessors are found that contradict this unreachability, they are
   * recursively handled in the same fashion, but at one level lower than their successors. <br>
   * This continues until the original states could be blocked, or a initial-state predecessor is
   * found. In this situation, a counterexample is created.
   *
   * @param pStatesToBlock The states that should be blocked at the highest level.
   * @return True if the states could be blocked. False if a counterexample is found.
   */
  private boolean backwardblock(StatesWithLocation pStatesToBlock, ReachedSet pReached)
      throws SolverException, InterruptedException, CPAEnabledAnalysisPropertyViolationException,
          CPAException {

    PriorityQueue<ProofObligation> proofObligationQueue = new PriorityQueue<>();
    proofObligationQueue.offer(new ProofObligation(frameSet.getFrontierLevel(), pStatesToBlock));

    // Recursively block bad states.
    while (!proofObligationQueue.isEmpty()) {
      ProofObligation p =
          proofObligationQueue.poll(); // Inspect proof obligation with lowest frame level.
      logger.log(Level.ALL, "Current obligation : ", p);

      // Frame level 0 => counterexample found
      if (p.getFrameLevel() == 0) {
        assert pdrSolver.isInitial(p.getState().getAbstract());
        analyzeCounterexample(p, pReached);
        return false;
      }

      // States can be blocked at level l if their negation is inductive relative to F_(l-1).
      // If they are not, a predecessor in F_(l-1) exists and should be blocked at level l-1 first.
      ConsecutionResult result = pdrSolver.consecution(p.getFrameLevel() - 1, p.getState());

      if (result.wasConsecutionSuccessful()) {
        BooleanFormula blockableStates = result.getResult().getAbstract();
        logger.log(Level.ALL, "Blocking states : ", blockableStates);
        frameSet.blockStates(blockableStates, p.getFrameLevel());

        // A bad state should be blocked at all known levels.
        if (p.getFrameLevel() < frameSet.getFrontierLevel()) {
          proofObligationQueue.offer(p.rescheduleToNextLevel());
        }
      } else {
        StatesWithLocation predecessorStates = result.getResult();
        logger.log(Level.ALL, "Found predecessor : ", predecessorStates.getAbstract());
        ProofObligation blockPredecessorStates =
            new ProofObligation(p.getFrameLevel() - 1, predecessorStates, p);
        proofObligationQueue.offer(blockPredecessorStates);
        proofObligationQueue.offer(p);
      }
    }
    return true;
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    if (algorithm instanceof StatisticsProvider) {
      ((StatisticsProvider) algorithm).collectStatistics(pStatsCollection);
    }
    pStatsCollection.add(compositeStats);
  }

  /**
   * Analyzes the counterexample trace represented by the given proof obligation, which is the start
   * of a chain of obligations whose respective predecessors lead to a target-location.
   *
   * <p>During the analysis, it populates the given reached set with the states along the error
   * trace.
   *
   * @param pFinalFailingObligation The proof obligation failing at the start location.
   * @param pTargetReachedSet The reached set to copy the states towards the error state into.
   * @throws InterruptedException If the analysis of the counterexample is interrupted.
   * @throws CPAException If an exception occurs during the analysis of the counterexample.
   */
  private void analyzeCounterexample(
      ProofObligation pFinalFailingObligation, ReachedSet pTargetReachedSet)
      throws CPAException, InterruptedException, SolverException {

    // Reconstruct error trace from start location to direct error predecessor.
    List<BlockWithConcreteState> blocks = Lists.newArrayList();
    StatesWithLocation lastStateInformation = pFinalFailingObligation.getState();
    ProofObligation currentObligation = pFinalFailingObligation;

    // Get block from lastStateInformation to the cause of currentObligation.
    while (currentObligation.getCause().isPresent()) {
      currentObligation = currentObligation.getCause().get();
      Block connectionBlock =
          PDRUtils.getDirectBlockToLocation(
                  lastStateInformation,
                  currentObligation.getState().getLocation()::equals,
                  stepwiseTransition,
                  fmgr,
                  solver)
              .orElseThrow(IllegalStateException::new);

      blocks.add(new BlockWithConcreteState(connectionBlock, lastStateInformation.getConcrete()));
      lastStateInformation = currentObligation.getState();
    }

    // Add block from direct error predecessor to target-location to complete error trace.
    StatesWithLocation directErrorPredecessor = lastStateInformation;
    Block blockToTargetLocation =
        PDRUtils.getDirectBlockToTargetLocation(
                directErrorPredecessor, transition, stepwiseTransition, fmgr, solver)
            .orElseThrow(IllegalStateException::new);
    blocks.add(
        new BlockWithConcreteState(blockToTargetLocation, directErrorPredecessor.getConcrete()));

    analyzeCounterexample(blocks, pTargetReachedSet);
  }

  /**
   * Analyzes the counterexample trace represented by the given list of blocks from the program
   * start to an error state and populates the given reached set with the states along the error
   * trace.
   *
   * @param pBlocks The blocks from the program start to the error state.
   * @param pTargetReachedSet The reached set to copy the states towards the error state into.
   * @throws InterruptedException If the analysis of the counterexample is interrupted.
   * @throws CPATransferException If an exception occurs during the analysis of the counterexample.
   */
  private void analyzeCounterexample(
      List<BlockWithConcreteState> pBlocks, ReachedSet pTargetReachedSet)
      throws CPAException, InterruptedException {

    stats.errorPathCreation.start();

    logger.log(Level.INFO, "Error found, creating error path");

    List<ARGPath> paths = Lists.newArrayListWithCapacity(pBlocks.size());
    try (ProverEnvironment prover = solver.newProverEnvironment(ProverOptions.GENERATE_MODELS)) {
      for (BlockWithConcreteState blockWithConcreteState : pBlocks) {
        Block block = blockWithConcreteState.block;
        List<ValueAssignment> model;
        // Reinstantiate state formula; the indices may be wrong here
        // due to the differences between the global transition relation and specific blocks.
        // The state formula still contains the artificial PC variable;
        // we could drop it, but it does not hurt either
        BooleanFormula stateFormula = fmgr.uninstantiate(blockWithConcreteState.concreteState);
        stateFormula = fmgr.instantiate(stateFormula, block.getUnprimedContext().getSsa());
        BooleanFormula pathFormula = block.getFormula();
        boolean branchingFormulaPushed = false;
        try {
          // Push predecessor state
          prover.push(stateFormula);
          // Push path formula
          prover.push(pathFormula);
          boolean satisfiable = !prover.isUnsat();
          if (!satisfiable) {
            // should not occur
            logger.log(
                Level.WARNING,
                "Counterexample export failed because the counterexample is spurious!");
            return;
          }

          // get the branchingFormula
          // this formula contains predicates for all branches we took
          // this way we can figure out which branches make a feasible path
          BooleanFormula branchingFormula =
              pfmgr.buildBranchingFormula(
                  AbstractStates.projectToType(block.getReachedSet(), ARGState.class).toSet());

          prover.push(branchingFormula);
          branchingFormulaPushed = true;
          // need to ask solver for satisfiability again,
          // otherwise model doesn't contain new predicates
          boolean stillSatisfiable = !prover.isUnsat();

          if (!stillSatisfiable) {
            // should not occur
            logger.log(
                Level.WARNING,
                "Could not create error path information because of inconsistent branching information!");
            return;
          }

          model = prover.getModelAssignments();

        } catch (SolverException e) {
          logger.log(Level.WARNING, "Solver could not produce model, cannot create error path.");
          logger.logDebugException(e);
          return;

        } finally {
          if (branchingFormulaPushed) {
            prover.pop(); // remove branching formula
          }
          prover.pop(); // remove path formula
          prover.pop(); // remove predecessor state
        }

        // get precise error path
        Map<Integer, Boolean> branchingInformation =
            pfmgr.getBranchingPredicateValuesFromModel(model);

        boolean isLastPart = paths.size() == pBlocks.size() - 1;
        ARGPath targetPath =
            ARGUtils.getPathFromBranchingInformation(
                AbstractStates.extractStateByType(block.getPredecessor(), ARGState.class),
                FluentIterable.from(block.getReachedSet()).toSet(),
                branchingInformation,
                isLastPart);
        paths.add(targetPath);
      }
    }

    // This temp file will be automatically deleted when the try block terminates.
    try (DeleteOnCloseFile automatonFile =
        TempFile.builder()
            .prefix("counterexample-automaton")
            .suffix(".txt")
            .createDeleteOnClose()) {
      try (Writer w = IO.openOutputFile(automatonFile.toPath(), Charset.defaultCharset())) {
        ARGUtils.producePathAutomaton(w, paths, "ReplayAutomaton", null);
      }

      Specification lSpecification =
          Specification.fromFiles(
              specification.getProperties(),
              ImmutableList.of(automatonFile.toPath()),
              cfa,
              config,
              logger);
      CoreComponentsFactory factory =
          new CoreComponentsFactory(config, logger, shutdownNotifier, new AggregatedReachedSets());
      ConfigurableProgramAnalysis lCpas = factory.createCPA(cfa, lSpecification);
      Algorithm lAlgorithm = CPAAlgorithm.create(lCpas, logger, config, shutdownNotifier);
      pTargetReachedSet.add(
          lCpas.getInitialState(cfa.getMainFunction(), StateSpacePartition.getDefaultPartition()),
          lCpas.getInitialPrecision(
              cfa.getMainFunction(), StateSpacePartition.getDefaultPartition()));

      lAlgorithm.run(pTargetReachedSet);
    } catch (IOException e) {
      throw new CPAException("Could not reply error path", e);
    } catch (InvalidConfigurationException e) {
      throw new CPAException("Invalid configuration in replay config: " + e.getMessage(), e);
    } finally {
      stats.errorPathCreation.stop();
    }

  }

  private static class PDRStatistics implements Statistics {

    private final Timer errorPathCreation = new Timer();

    @Override
    public void printStatistics(PrintStream pOut, Result pResult, UnmodifiableReachedSet pReached) {
      if (errorPathCreation.getNumberOfIntervals() > 0) {
        pOut.println("Time for error path creation:        " + errorPathCreation);
      }
    }

    @Override
    public @Nullable String getName() {
      return "PDR algorithm";
    }
  }

  private static class BlockWithConcreteState {

    private final Block block;

    private final BooleanFormula concreteState;

    private BlockWithConcreteState(Block pBlock, BooleanFormula pConcreteState) {
      block = Objects.requireNonNull(pBlock);
      concreteState = Objects.requireNonNull(pConcreteState);
    }

  }
}
