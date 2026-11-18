| Type | Requête | 100 livres | 250 livres | 500 livres | 1000 livres | 1684 livres |
|---|---|---|---|---|---|---|
| motcle | `sargon` | 0.032 ± 0.03 ms | 0.019 ± 0.013 ms | 0.001 ± 0 ms | 0.004 ± 0.001 ms | 0.003 ± 0.001 ms |
| motcle | `babylon` | 0.044 ± 0.014 ms | 0.014 ± 0.004 ms | 0.012 ± 0.005 ms | 0.021 ± 0.002 ms | 0.073 ± 0.002 ms |
| motcle | `whale` | 0.061 ± 0.021 ms | 0.019 ± 0.001 ms | 0.014 ± 0.003 ms | 0.039 ± 0.016 ms | 0.068 ± 0.025 ms |
| motcle | `elizabeth` | 0.036 ± 0.009 ms | 0.029 ± 0.038 ms | 0.02 ± 0.006 ms | 0.076 ± 0.017 ms | 0.092 ± 0.032 ms |
| motcle | `science` | 0.112 ± 0.05 ms | 0.082 ± 0.026 ms | 0.052 ± 0.006 ms | 0.18 ± 0.045 ms | 0.243 ± 0.018 ms |
| motcle | `love` | 0.148 ± 0.074 ms | 0.122 ± 0.036 ms | 0.127 ± 0.01 ms | 0.414 ± 0.11 ms | 0.549 ± 0.15 ms |
| contient | `babylon` | 16.8 ± 4 ms | 20.7 ± 1.1 ms | 32.8 ± 2.9 ms | 71.6 ± 10 ms | 66 ± 9.7 ms |
| contient | `ship` | 13.8 ± 1.8 ms | 24.7 ± 3.1 ms | 33.3 ± 4 ms | 65.2 ± 4.7 ms | 70.6 ± 6.6 ms |
| contient | `tion` | 15.8 ± 2.2 ms | 27.6 ± 2.5 ms | 36.6 ± 2.2 ms | 66.2 ± 2.3 ms | 79 ± 7.5 ms |
| regex_index | `s(a|g|r)+on` | 6.81 ± 2.5 ms | 8.87 ± 1.7 ms | 10.8 ± 0.3 ms | 20 ± 3.7 ms | 22 ± 0.57 ms |
| regex_index | `whal(e|es|ing)` | 5.7 ± 1 ms | 7.22 ± 0.52 ms | 14.1 ± 1 ms | 22.7 ± 4.7 ms | 22.8 ± 2.4 ms |
| regex_index | `(a|e|i|o|u)(a|e|i|o|u)(a|e|i|o|u)` | 7.07 ± 1.2 ms | 12 ± 1.9 ms | 11.6 ± 1.6 ms | 25.1 ± 1.7 ms | 24.2 ± 1.4 ms |
| regex_index | `pre.*tion` | 5.74 ± 1.4 ms | 10.8 ± 1.2 ms | 11.3 ± 0.31 ms | 26.2 ± 5.4 ms | 27.6 ± 5.2 ms |
| regex_texte | `Sargon` | 54.7 ± 14 ms | 127 ± 5.8 ms | 225 ± 36 ms | 574 ± 72 ms | 1.07e+03 ± 2e+02 ms |
| regex_texte | `S(a|g|r)+on` | 48.2 ± 6.9 ms | 105 ± 8.5 ms | 198 ± 9.7 ms | 627 ± 38 ms | 1.13e+03 ± 1.7e+02 ms |
| regex_texte | `whal(e|es|ing)` | 46.7 ± 3 ms | 89.1 ± 9.3 ms | 194 ± 31 ms | 573 ± 75 ms | 801 ± 43 ms |
