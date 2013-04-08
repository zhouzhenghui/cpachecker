/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2012  Dirk Beyer
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
package org.sosy_lab.cpachecker.cpa.explicit.refiner;

import java.util.Collection;

import javax.annotation.Nullable;

import org.sosy_lab.common.LogManager;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.core.CounterexampleInfo;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.interfaces.StatisticsProvider;
import org.sosy_lab.cpachecker.core.interfaces.WrapperCPA;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.cpa.arg.ARGPath;
import org.sosy_lab.cpachecker.cpa.arg.ARGReachedSet;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.cpa.arg.AbstractARGBasedRefiner;
import org.sosy_lab.cpachecker.cpa.explicit.ExplicitCPA;
import org.sosy_lab.cpachecker.cpa.explicit.ExplicitPrecision;
import org.sosy_lab.cpachecker.cpa.explicit.refiner.utils.ExplictFeasibilityChecker;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateCPA;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateCPARefiner;
import org.sosy_lab.cpachecker.cpa.predicate.RefinementStrategy;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.util.Precisions;
import org.sosy_lab.cpachecker.util.predicates.AbstractionManager;
import org.sosy_lab.cpachecker.util.predicates.FormulaManagerFactory;
import org.sosy_lab.cpachecker.util.predicates.PathFormulaManagerImpl;
import org.sosy_lab.cpachecker.util.predicates.Solver;
import org.sosy_lab.cpachecker.util.predicates.interfaces.PathFormulaManager;
import org.sosy_lab.cpachecker.util.predicates.interfaces.view.FormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.interpolation.InterpolationManager;

import com.google.common.collect.Multimap;

/**
 * Refiner implementation that delegates to {@link ExplicitInterpolationBasedExplicitRefiner},
 * and if this fails, optionally delegates also to {@link PredicatingExplicitRefiner}.
 */
public class DelegatingExplicitRefiner extends AbstractARGBasedRefiner implements StatisticsProvider {

  /**
   * refiner used for explicit interpolation refinement
   */
  private ExplicitInterpolationBasedExplicitRefiner explicitInterpolatingRefiner;

  /**
   * backup-refiner used for predicate refinement, when explicit refinement fails (due to lack of expressiveness)
   */
  private PredicateCPARefiner predicatingRefiner;

  public static DelegatingExplicitRefiner create(ConfigurableProgramAnalysis cpa) throws CPAException, InvalidConfigurationException {
    if (!(cpa instanceof WrapperCPA)) {
      throw new InvalidConfigurationException(DelegatingExplicitRefiner.class.getSimpleName() + " could not find the ExplicitCPA");
    }

    ExplicitCPA explicitCpa = ((WrapperCPA)cpa).retrieveWrappedCpa(ExplicitCPA.class);
    if (explicitCpa == null) {
      throw new InvalidConfigurationException(DelegatingExplicitRefiner.class.getSimpleName() + " needs a ExplicitCPA");
    }

    DelegatingExplicitRefiner refiner = initialiseExplicitRefiner(cpa, explicitCpa);
    explicitCpa.getStats().addRefiner(refiner);

    return refiner;
  }

  private static DelegatingExplicitRefiner initialiseExplicitRefiner(
      ConfigurableProgramAnalysis cpa, ExplicitCPA explicitCpa)
          throws CPAException, InvalidConfigurationException {
    Configuration config                        = explicitCpa.getConfiguration();
    LogManager logger                           = explicitCpa.getLogger();

    PathFormulaManager pathFormulaManager;
    PredicateCPARefiner backupRefiner    = null;

    PredicateCPA predicateCpa = ((WrapperCPA)cpa).retrieveWrappedCpa(PredicateCPA.class);
    if (predicateCpa != null) {

      FormulaManagerFactory factory               = predicateCpa.getFormulaManagerFactory();
      FormulaManagerView formulaManager           = predicateCpa.getFormulaManager();
      Solver solver                               = predicateCpa.getSolver();
      AbstractionManager absManager               = predicateCpa.getAbstractionManager();
      pathFormulaManager                          = predicateCpa.getPathFormulaManager();

      InterpolationManager manager = new InterpolationManager(
          formulaManager,
          pathFormulaManager,
          solver,
          factory,
          config,
          logger);

      RefinementStrategy backupRefinementStrategy = new PredicatingExplicitRefinementStrategy(
          config,
          logger,
          formulaManager,
          absManager);

      backupRefiner = new PredicateCPARefiner(
          config,
          logger,
          cpa,
          manager,
          formulaManager,
          pathFormulaManager,
          backupRefinementStrategy);

    } else {
      FormulaManagerFactory factory         = new FormulaManagerFactory(config, logger);
      FormulaManagerView formulaManager     = new FormulaManagerView(factory.getFormulaManager(), config, logger);
      pathFormulaManager                    = new PathFormulaManagerImpl(formulaManager, config, logger, explicitCpa.getMachineModel());
    }

    return new DelegatingExplicitRefiner(
        config,
        logger,
        cpa,
        pathFormulaManager,
        backupRefiner);
  }

  protected DelegatingExplicitRefiner(
      final Configuration config,
      final LogManager logger,
      final ConfigurableProgramAnalysis cpa,
      final PathFormulaManager pathFormulaManager,
      @Nullable final PredicateCPARefiner pBackupRefiner) throws CPAException, InvalidConfigurationException {

    super(cpa);

    explicitInterpolatingRefiner = new ExplicitInterpolationBasedExplicitRefiner(config, pathFormulaManager);

    predicatingRefiner = pBackupRefiner;
  }

  @Override
  protected CounterexampleInfo performRefinement(final ARGReachedSet reached, final ARGPath errorPath)
      throws CPAException, InterruptedException {

    // if path infeasible, refine the precision
    if(!isPathFeasable(errorPath)) {
      UnmodifiableReachedSet reachedSet = reached.asReachedSet();
      Precision precision = reachedSet.getPrecision(reachedSet.getLastState());

      Multimap<CFANode, String> increment = explicitInterpolatingRefiner.determinePrecisionIncrement(reachedSet, errorPath);
      ARGState interpolationPoint = explicitInterpolatingRefiner.determineInterpolationPoint(errorPath);

      ExplicitPrecision explicitPrecision = Precisions.extractPrecisionByType(precision, ExplicitPrecision.class);

      explicitPrecision = new ExplicitPrecision(explicitPrecision, increment);
      reached.removeSubtree(interpolationPoint, explicitPrecision);

      return CounterexampleInfo.spurious();
    }

    // if explicit analysis claims that path is feasible, refine predicate analysis if available
    else {
      if (predicatingRefiner == null) {
        return CounterexampleInfo.feasible(errorPath, null);
      }

      else {
        return predicatingRefiner.performRefinement(reached, errorPath);
      }
    }
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    pStatsCollection.add(explicitInterpolatingRefiner);
    if (predicatingRefiner != null) {
      predicatingRefiner.collectStatistics(pStatsCollection);
    }
  }

  /**
   * This method checks if the given path is feasible, when not tracking the given set of variables.
   *
   * @param path the path to check
   * @return true, if the path is feasible, else false
   * @throws CPAException if the path check gets interrupted
   */
  private boolean isPathFeasable(ARGPath path) throws CPAException {
    try {
      // create a new ExplicitPathChecker, which does not track any of the given variables
      ExplictFeasibilityChecker checker = new ExplictFeasibilityChecker();

      return checker.isFeasible(path);
    }
    catch (InterruptedException e) {
      throw new CPAException("counterexample-check failed: ", e);
    }
  }
}