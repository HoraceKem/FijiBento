import numpy as np
from scipy.sparse.linalg import cg, LinearOperator
from scipy.sparse import diags
from scipy.optimize import fmin_cg

PrintLambda = False

def hessian_free(f=None, x0=None, fprime=None, fhessp=None, callback=None, maxiter=100):
    x = x0.copy()
    lbd = 1.0e-5 # regularization of hessian
    delta = np.zeros_like(x)
    stepsize = 0.95
    old_f = f(x0)

    for iter in range(maxiter):
        gradient = fprime(x)

        Bflat = lambda v : np.sum(0.5 * v * fhessp(x, v) + gradient * v)
        A = LinearOperator((x0.size, x0.size), lambda v: fhessp(x, v) + lbd * v)

        # Empirical Fisher diagonal preconditioner
        g = (gradient.copy() + 0.00001)
        M = diags([1.0 / g**2], [0])

        # start with offset equivalent to previous delta
        new_delta = stepsize * cg(A, -gradient, x0=delta, maxiter=100, M=M)[0]

        x_new = x + new_delta
        # update lbd using Levenberg-Marquardt
        new_f = f(x_new)
        rho = (old_f - new_f) / -Bflat(new_delta)
        if (rho < 0.25) or (new_f >= old_f): lbd = lbd * 2
        elif rho > 0.75: lbd *= 0.5
        if PrintLambda:
            print "actual reduction", (old_f - new_f), "vs", -Bflat(new_delta), "lbd", lbd, "linear", np.sum(new_delta * gradient)

        if new_f < old_f:
            x = x_new
            old_f = f(x)
            delta = new_delta
            if callback is not None:
                callback(x)
    return x