#ifndef _KISS_FFT_GUTS_H
#define _KISS_FFT_GUTS_H

#define MIN(n, m) ((n) < (m) ? (n) : (m))
#define MAX(n, m) ((n) > (m) ? (n) : (m))

/*
  Explanation of macros dealing with complex math:

   C_MUL(m,a,b)         : m = a*b
   C_FIXDIV( c , div )  : if a fixed point impl, c /= div. noop otherwise
   C_SUB( res, a,b)     : res = a - b
   C_SUBFROM( res , a)  : res -= a
   C_ADDTO( res , a)    : res += a
   * */
#ifdef FIXED_POINT
#include <stdint.h>
#if (FIXED_POINT == 32)
#define FRACBITS 31
#define SAMPPROD int64_t
#define SAMP_MAX 2147483647
#else
#define FRACBITS 15
#define SAMPPROD int32_t
#define SAMP_MAX 32767
#endif

#define SAMP_MIN -SAMP_MAX

#if defined(CHECK_OVERFLOW)
#define CHECK_OVERFLOW_OP(a, op, b)                                            \
  if ((a op b) > SAMP_MAX || (a op b) < SAMP_MIN) {                            \
    fprintf(stderr,                                                            \
            "WARNING:overflow @ " __FILE__ "(%d): (%d " #op " %d) = %ld\n",    \
            __LINE__, a, b, (long)(a op b));                                   \
  }
#endif

#define smul(a, b) ((SAMPPROD)(a) * (b))
#define sround(x) (kiss_fft_scalar)(((x) + (1 << (FRACBITS - 1))) >> FRACBITS)

#define S_MUL(a, b) sround(smul(a, b))

#define C_MUL(m, a, b)                                                         \
  do {                                                                         \
    (m).r = sround(smul((a).r, (b).r) - smul((a).i, (b).i));                   \
    (m).i = sround(smul((a).r, (b).i) + smul((a).i, (b).r));                   \
  } while (0)

#define DIVSCALAR(x, k) (x) = sround(smul(x, SAMP_MAX / k))

#define C_FIXDIV(c, div)                                                       \
  do {                                                                         \
    DIVSCALAR((c).r, div);                                                     \
    DIVSCALAR((c).i, div);                                                     \
  } while (0)

#define C_MULBYSCALAR(c, s)                                                    \
  do {                                                                         \
    (c).r = sround(smul((c).r, s));                                            \
    (c).i = sround(smul((c).i, s));                                            \
  } while (0)

#else /* not FIXED_POINT*/

#define S_MUL(a, b) ((a) * (b))
#define C_MUL(m, a, b)                                                         \
  do {                                                                         \
    (m).r = (a).r * (b).r - (a).i * (b).i;                                     \
    (m).i = (a).r * (b).i + (a).i * (b).r;                                     \
  } while (0)
#define C_FIXDIV(c, div) /* NOOP */
#define C_MULBYSCALAR(c, s)                                                    \
  do {                                                                         \
    (c).r *= (s);                                                              \
    (c).i *= (s);                                                              \
  } while (0)
#endif

#ifndef CHECK_OVERFLOW_OP
#define CHECK_OVERFLOW_OP(a, op, b) /* noop */
#endif

#define C_ADD(res, a, b)                                                       \
  do {                                                                         \
    CHECK_OVERFLOW_OP((a).r, +, (b).r)                                         \
    CHECK_OVERFLOW_OP((a).i, +, (b).i)                                         \
    (res).r = (a).r + (b).r;                                                   \
    (res).i = (a).i + (b).i;                                                   \
  } while (0)
#define C_SUB(res, a, b)                                                       \
  do {                                                                         \
    CHECK_OVERFLOW_OP((a).r, -, (b).r)                                         \
    CHECK_OVERFLOW_OP((a).i, -, (b).i)                                         \
    (res).r = (a).r - (b).r;                                                   \
    (res).i = (a).i - (b).i;                                                   \
  } while (0)
#define C_ADDTO(res, a)                                                        \
  do {                                                                         \
    CHECK_OVERFLOW_OP((res).r, +, (a).r)                                       \
    CHECK_OVERFLOW_OP((res).i, +, (a).i)                                       \
    (res).r += (a).r;                                                          \
    (res).i += (a).i;                                                          \
  } while (0)

#define C_SUBFROM(res, a)                                                      \
  do {                                                                         \
    CHECK_OVERFLOW_OP((res).r, -, (a).r)                                       \
    CHECK_OVERFLOW_OP((res).i, -, (a).i)                                       \
    (res).r -= (a).r;                                                          \
    (res).i -= (a).i;                                                          \
  } while (0)

#ifdef FIXED_POINT
#define KISS_FFT_COS(phase) floor(.5 + SAMP_MAX * cos(phase))
#define KISS_FFT_SIN(phase) floor(.5 + SAMP_MAX * sin(phase))
#define HALF_OF(x) ((x) >> 1)
#elif defined(USE_SIMD)
#define KISS_FFT_COS(phase) _mm_set1_ps(cos(phase))
#define KISS_FFT_SIN(phase) _mm_set1_ps(sin(phase))
#define HALF_OF(x) ((x) * _mm_set1_ps(.5))
#else
#define KISS_FFT_COS(phase) (kiss_fft_scalar) cos(phase)
#define KISS_FFT_SIN(phase) (kiss_fft_scalar) sin(phase)
#define HALF_OF(x) ((x) * .5)
#endif

#define kf_cexp(x, phase)                                                      \
  do {                                                                         \
    (x)->r = KISS_FFT_COS(phase);                                              \
    (x)->i = KISS_FFT_SIN(phase);                                              \
  } while (0)

/* a debugging function */
#define pcpx(c)                                                                \
  fprintf(stderr, "%g + %gi\n", (double)((c)->r), (double)((c)->i))

#ifdef KISS_FFT_USE_ALLOCA
// define this to allow use of alloca instead of malloc for temporary buffers
// Temporary buffers are used in two case:
// 1. FFT sizes that have "bad" factors. i.e. not 2,3,5
// 2. "in-place" FFTs.  Notice the quotes, since kiss fft does not really do an
// in-place transform.
#include <alloca.h>
#define KISS_FFT_MALLOC alloca
#define KISS_FFT_FREE(x) /* free is NULL */
#else
#define KISS_FFT_MALLOC malloc
#define KISS_FFT_FREE free
#endif

#ifdef KISS_FFT_SHARED
#define KISS_FFT_API __attribute__((visibility("default")))
#else
#define KISS_FFT_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

/*
 *
 *  KISS_FFT_INTERNAL_STATE
 *
 *  Structure needed for the complex FFT
 *
 * */
struct kiss_fft_state {
  int nfft;
  int inverse;
  int factors[2 * 32];
  kiss_fft_cpx twiddles[1];
};

#ifdef __cplusplus
}
#endif

static void kf_bfly_generic(kiss_fft_cpx *Fout, const size_t fstride,
                            const kiss_fft_cfg st, int m, int p) {
  /*
   *
   *  generic butterfly functionality
   *
   *  This handles any integer-radix butterfly.
   *  The performance is very poor for prime factors > 5
   *
   * */
  int u, k, q1, q;
  kiss_fft_cpx *twiddles = st->twiddles;
  kiss_fft_cpx t;
  int Norig = st->nfft;

  kiss_fft_cpx *scratch =
      (kiss_fft_cpx *)KISS_FFT_MALLOC(sizeof(kiss_fft_cpx) * p);

  for (u = 0; u < m; ++u) {
    k = u;
    for (q1 = 0; q1 < p; ++q1) {
      scratch[q1] = Fout[k];
      C_FIXDIV(scratch[q1], p);
      k += m;
    }

    k = u;
    for (q1 = 0; q1 < p; ++q1) {
      int twidx = 0;
      Fout[k] = scratch[0];
      for (q = 1; q < p; ++q) {
        twidx += fstride * k;
        if (twidx >= Norig)
          twidx -= Norig;
        C_MUL(t, scratch[q], twiddles[twidx]);
        C_ADDTO(Fout[k], t);
      }
      k += m;
    }
  }
  KISS_FFT_FREE(scratch);
}

#endif
